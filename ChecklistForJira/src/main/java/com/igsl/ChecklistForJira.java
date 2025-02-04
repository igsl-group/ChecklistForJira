package com.igsl;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.logging.log4j2.Log4j2Impl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.postgresql.Driver;

import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.igsl.CLI.CLIOptions;
import com.igsl.json.ChecklistForJiraData;
import com.igsl.json.ChecklistItem;
import com.igsl.mybatis.CustomField;
import com.igsl.mybatis.DataMapper;
import com.igsl.mybatis.IssueType;
import com.igsl.mybatis.Project;

/**
 * Replacement for ChecklistForJira.ps1.
 * The PowerShell script has a bug - it does not correctly associate projects with Checklist for Jira fields.
 * The script is also cumbersome to use - it requires SQL executed and saved as CSV beforehand.
 * 
 * Might as well do it all inside a Java program.
 */
public class ChecklistForJira {
	
	private static final Logger LOGGER = LogManager.getLogger();
	private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyyMMdd-HHmmss");
	private static final ObjectMapper OM = new ObjectMapper()
			.enable(Feature.ALLOW_COMMENTS)
			.enable(SerializationFeature.INDENT_OUTPUT)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
	
	private static final int BUFFER_SIZE = 10240;	
	private static final int MAX_COL_SIZE = 20000;	// Huge text is not supported well by Excel
	private static final int MAX_TEMPLATE_NAME_LENGTH = 50;	// Max Checklist for Jira template name length
	
	public static SqlSessionFactory setupMyBatis(Config conf) throws Exception {
		PooledDataSource ds = new PooledDataSource();
		ds.setDriver(Driver.class.getCanonicalName());
		ds.setUrl(conf.getSourceDatabaseURL());
		String dbUser = conf.getSourceDatabaseUser();
		if (dbUser == null || dbUser.isEmpty()) {
			dbUser = Console.readLine("DataCenter database user: ");
			conf.setSourceDatabaseUser(dbUser);
		}
		String dbPassword = conf.getSourceDatabasePassword();
		if (dbPassword == null || dbPassword.isEmpty()) {
			dbPassword = new String(Console.readPassword("DataCenter database password for " + dbUser + ": "));
			conf.setSourceDatabasePassword(dbPassword);
		}
		ds.setUsername(dbUser);
		ds.setPassword(dbPassword);
		TransactionFactory transactionFactory = new JdbcTransactionFactory();
		Environment environment = new Environment("development", transactionFactory, ds);
		Configuration configuration = new Configuration(environment);
		configuration.addMapper(DataMapper.class);
		configuration.setLogImpl(Log4j2Impl.class);
		configuration.setCallSettersOnNulls(true);
		return new SqlSessionFactoryBuilder().build(configuration);
	}
	

	private static List<CustomField> getChecklistFileds(Config conf) throws Exception {
		List<CustomField> result = new ArrayList<>();
		SqlSessionFactory factory = setupMyBatis(conf);
		try (SqlSession session = factory.openSession()) {
			// Get filter info from source
			DataMapper mapper = session.getMapper(DataMapper.class);
			result = mapper.getCustomFieldUsage();
		}
		return result;
	}

	private static List<String> splitChecklistTemplate(String template) {
		List<String> result = new ArrayList<>();
		while (template.length() > 0) {
			if (template.length() > MAX_COL_SIZE) {
				result.add(template.substring(0, MAX_COL_SIZE + 1));
				template = template.substring(MAX_COL_SIZE + 1);
			} else {
				result.add(template);
				return result;
			}
		}
		return result;
	}
	
	private static String getTemplateName(String customFieldName, int contextCount, String contextId) {
		String result;
		if (contextCount > 1) {
			result = StringUtils.left(
						customFieldName, 
						MAX_TEMPLATE_NAME_LENGTH - 1 - contextId.length()) + 
					":" + contextId;
		} else {
			// Use custom field name
			result = StringUtils.left(customFieldName, MAX_TEMPLATE_NAME_LENGTH);
		}
		return result;
	}
	
	public static Path gunzip(Path extractDir, Path gzFile) throws Exception {
		String baseName = FilenameUtils.removeExtension(gzFile.toFile().getName());
		Path out = extractDir.resolve(baseName);
		try (	GZIPInputStream gin = new GZIPInputStream(new FileInputStream(gzFile.toFile()));
				BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out.toFile())); 
				) {
			byte[] buffer = new byte[BUFFER_SIZE];
			int size = -1;
			do {
				size = gin.read(buffer);
				if (size > 0) {
					bos.write(buffer, 0, size);
				}
			} while (size != -1);
		}
		return out;
	}
	
	private static void exportUsage(
			Config conf, 
			List<CustomField> fieldList, 
			String folder) throws Exception {
		Map<String, CustomField> fieldMap = fieldList.stream().collect(
				Collectors.toMap(CustomField::getFieldId, item -> item));
		// Output to CSV
		Date now = new Date();
		Log.info(LOGGER, "Reading GZ from [" + folder + "]");
		Path gzDir = Paths.get(folder);
		Path extractDir = Paths.get("GZ." + SDF.format(now));
		Path csvProject = Paths.get("ChecklistProject." + SDF.format(now) + ".csv"); 
		Path csvTemplate = Paths.get("ChecklistTemplate." + SDF.format(now) + ".csv"); 
		Path csvUsage = Paths.get("ChecklistUsage." + SDF.format(now) + ".csv"); 
		CSVFormat fmtTemplate = CSV.getCSVWriteFormat(Arrays.asList(
					"Template Name",
					"Template Content"
				));
		CSVFormat fmtUsage = CSV.getCSVWriteFormat(Arrays.asList(
					"Project Key",
					"Context Applied to All Projects",
					"Issue Type(s)",
					"Field Name",
					"Context Name", 
					"Template Name",
					"Template Empty"
				));
		CSVFormat fmtProject = CSV.getCSVWriteFormat(Arrays.asList(
					"Project Key"
				));
		CSVPrinter template = null;
		try {
			Files.createDirectory(extractDir);
			try (	FileWriter fwProject = new FileWriter(csvProject.toFile());
					CSVPrinter project = new CSVPrinter(fwProject, fmtProject);					
					FileWriter fwTemplate = new FileWriter(csvTemplate.toFile());
					FileWriter fwUsage = new FileWriter(csvUsage.toFile()); 
					CSVPrinter usage = new CSVPrinter(fwUsage, fmtUsage)) {
				// Projects that uses Checklist for Jira
				Set<String> projectList = new HashSet<>();
				// Unzip GZ files
				File[] gzList = gzDir.toFile().listFiles((dir, name) -> {
					return name.toLowerCase().endsWith(".gz");
				});
				for (File gzFile : gzList) {
					try {
						gunzip(extractDir, gzFile.toPath());
					} catch (Exception e) {
						Log.error(LOGGER, "Error processing " + gzFile.toString() + ", file ignored", e);
					}
				}
				// Process all files
				ObjectReader reader = OM.readerFor(ChecklistForJiraData.class);
				File[] extractedList = extractDir.toFile().listFiles();
				// First pass to count no. of contexts for each custom field
				Map<String, List<String>> fieldToContextMap = new HashMap<>();
				int maxAdditionalTemplateCol = 0;
				for (File extractedFile : extractedList) {
					ChecklistForJiraData data = null;
					try (FileInputStream in = new FileInputStream(extractedFile)) {
						data = reader.readValue(in);
					} catch (Exception ex) {
						Log.error(LOGGER, "Error processing " + extractedFile.toString() + ", file ignored", ex);
						continue;
					}
					if (data.isManifest()) {
						String customFieldId = String.valueOf(data.getFieldConfig().getCustomFieldId());
						String contextId = String.valueOf(data.getFieldConfig().getId());
						if (!fieldToContextMap.containsKey(customFieldId)) {
							fieldToContextMap.put(customFieldId, new ArrayList<>());
						}
						fieldToContextMap.get(customFieldId).add(contextId);
						// Also count max. no. of additional columns for template content
						List<ChecklistItem> checklistItems = data.getGlobalItems();
						// Convert checklistItems to new format
						String newChecklist = ChecklistItem.convert(checklistItems);
						List<String> templateCols = splitChecklistTemplate(newChecklist);
						maxAdditionalTemplateCol = Math.max(maxAdditionalTemplateCol, templateCols.size() - 1);
					}
				}
				// Create template CSVPrinter
				List<String> templateColumnNames = new ArrayList<>();
				templateColumnNames.add("Template Name");
				templateColumnNames.add("Template Content");
				for (int i = 0; i < maxAdditionalTemplateCol; i++) {
					templateColumnNames.add("Template Content Con't #" + (i + 1));
				}
				fmtTemplate = CSV.getCSVWriteFormat(templateColumnNames);
				template = new CSVPrinter(fwTemplate, fmtTemplate);
				// Second pass to record template content and usage
				for (File extractedFile : extractedList) {
					// Extract and convert templates
					ChecklistForJiraData data = null;
					try (FileInputStream in = new FileInputStream(extractedFile)) {
						data = reader.readValue(in);
					} catch (Exception ex) {
						Log.error(LOGGER, "Error processing " + extractedFile.toString() + ", file ignored", ex);
						continue;
					}
					if (data.isManifest()) {
						Log.info(LOGGER, "Processing manifest [" + extractedFile + "]");
						String customFieldId = String.valueOf(data.getFieldConfig().getCustomFieldId());
						String customFieldName = data.getFieldConfig().getCustomFieldName();
						String contextId = String.valueOf(data.getFieldConfig().getId());
						String contextName = data.getFieldConfig().getName();
						int contextCount = fieldToContextMap.get(customFieldId).size();
						String templateName = getTemplateName(
								customFieldName, contextCount, contextId);
						List<ChecklistItem> checklistItems = data.getGlobalItems();
						// Convert checklistItems to new format
						String newChecklist = ChecklistItem.convert(checklistItems);
						// Find accurate mapping from template to projects
						if (!fieldMap.containsKey(customFieldId)) {
							// Custom field not found, sound alarm
							Log.error(LOGGER, 
									"Checklist field " + customFieldName + " (" + customFieldId + ") not found");
							continue;
						}
						CustomField cf = fieldMap.get(customFieldId);
						// Output template content
						List<String> templateCols = splitChecklistTemplate(newChecklist);
						templateCols.add(0, templateName);
						template.printRecord((Object[]) templateCols.toArray(new String[0]));
						Log.info(LOGGER, "Template [" + templateName + "] processed");
						// Record usage
						StringBuilder issueTypes = new StringBuilder();
						// Do not trust GZ, use field data
						issueTypes = new StringBuilder();
						for (Project p : cf.getProjectList()) {
							projectList.add(p.getProjectKey());
							for (IssueType it : p.getIssueTypeList()) {
								if (IssueType.ALL_ISSUE_TYPES_ID.equals(it.getIssueTypeId())) {
									issueTypes = new StringBuilder("\n").append(IssueType.ALL_ISSUE_TYPE_NAME);
									break;
								}
								issueTypes.append("\n").append(it.getIssueTypeName());
							}
							if (issueTypes.length() != 0) {
								issueTypes.delete(0, 1);
							} else if (issueTypes.length() == 0) {
								issueTypes.append(IssueType.ALL_ISSUE_TYPE_NAME);
							}
							usage.printRecord(
								p.getProjectKey(),
								true, 
								issueTypes.toString(), 
								customFieldName,
								contextName,
								templateName,
								(newChecklist.length() == 0)
								);
							Log.info(LOGGER, 
									"Template [" + templateName + "] is associated via workflow/screens with " + 
									p.getProjectKey() + " issue types [" + issueTypes.toString() + "]");
						}
						Log.info(LOGGER, 
								"Template [" + templateName + "] is associated with " + 
								cf.getProjectList().size() + " project(s)");
						/* Using GZ project/issue type data
							for (String issueTypeName : data.getFieldConfig().getIssueTypes().values()) {
								issueTypes.append("\n").append(issueTypeName);
							}
							if (issueTypes.length() != 0) {
								issueTypes.delete(0, 1);
							} else if (issueTypes.length() == 0) {
								issueTypes.append(IssueType.ALL_ISSUE_TYPE_NAME);
							}
							for (String p : data.getFieldConfig().getProjects().values()) {
								projectList.add(p);
								usage.printRecord(
										p,
										false,
										issueTypes.toString(), 
										customFieldName,
										contextName,
										templateName, 
										(newChecklist.length() == 0)
										);
								Log.info(LOGGER, 
										"Template [" + templateName + "] is associated via context with " + 
										p + " issue types [" + issueTypes.toString() + "]");
							}
							Log.info(LOGGER, 
									"Template [" + templateName + "] is associated with " + 
									data.getFieldConfig().getProjects().values().size() + " project(s)");
						*/
					}	// If manifest
				} // For all extracted files
				// Write project list
				for (String pKey : projectList) {
					project.printRecord(pKey);
				}				
			} // Try file outputs
		} finally {
			if (template != null) {
				template.close();
			}
			FileUtils.deleteDirectory(extractDir.toFile());
		}
		Log.info(LOGGER, "Checklist for Jira templates written to: " + csvTemplate.toString());
		Log.info(LOGGER, "Template usage written to: " + csvUsage.toString());
	}
	
	private static void exportFieldList(Config conf) {
		String fileName = "ChecklistField." + SDF.format(new Date()) + ".json";
		try (FileWriter fw = new FileWriter(fileName)) {
			List<CustomField> fieldList = getChecklistFileds(conf);
			OM.writeValue(fw, fieldList);
			Log.info(LOGGER, "Checklist for Jira fields exported to: " + fileName);
		} catch (Exception ex) {
			Log.error(LOGGER, "Error exporting Checklist for Jira fields", ex);
		}
	}
	
	private static List<CustomField> readFieldList(String fileName) {
		List<CustomField> result = null;
		try (FileReader fr = new FileReader(fileName)) {
			ObjectReader reader = OM.readerFor(CustomField.class);
			MappingIterator<CustomField> list = reader.readValues(fr);
			result = new ArrayList<>();
			while (list.hasNext()) {
				result.add(list.next());
			}
		} catch (Exception ex) {
			Log.error(LOGGER, "Error reading Checklist for Jira fields", ex);
		}
		return result;
	}
	
	private static Config parseConfig(CommandLine cmd) throws IOException {
		String configFile = cmd.getOptionValue(CLI.CONFIG_OPTION);
		ObjectReader reader = OM.readerFor(Config.class);
		Path p = Paths.get(configFile);
		Log.info(LOGGER, "Reading configuration file: [" + p.toAbsolutePath() + "]");
		try (FileReader fr = new FileReader(p.toFile())) {
			return reader.readValue(fr);
		}
	}
	
	private static void triggerExport(Config conf, List<CustomField> fieldList) {
		ExportResult result = new ExportResult();
		ExecutorService service = Executors.newFixedThreadPool(conf.getConcurrentExportCount());
		List<Future<ExportResult>> futureList = new ArrayList<>();
		for (CustomField field : fieldList) {
			futureList.add(service.submit(new ExportThread(
					conf, Paths.get(conf.getChecklistForJiraExportDir()), field, conf.getExportMaxWaitMS())));
		}
		while (!futureList.isEmpty()) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException iex) {
				Log.error(LOGGER, "Sleep interrupted", iex);
			}
			List<Future<ExportResult>> toRemove = new ArrayList<>();
			for (Future<ExportResult> future : futureList) {
				try {
					ExportResult er = future.get(1000, TimeUnit.MILLISECONDS);
					result.addAll(er);
					toRemove.add(future);
				} catch (TimeoutException tex) {
					// Ignore and wait
				} catch (Exception ex) {
					Log.error(LOGGER, "Thread execution failed", ex);
					toRemove.add(future);
				}
			}
			futureList.removeAll(toRemove);
		}
		service.shutdownNow();
		// Print result
		// Verified
		List<String> verifiedList = result.getResultMap().entrySet().stream().filter(entry -> {
			return (entry.getValue().size() != 0);
		}).map(Map.Entry::getKey).collect(Collectors.toList());
		Log.info(LOGGER, "Verified: ");
		if (verifiedList.size() != 0) {
			verifiedList.forEach(item -> {
				Log.info(LOGGER, "\t" + item);
			});
		} else {
			Log.info(LOGGER, "\t--None--"); 
		}		

		List<String> unverifiedList = result.getResultMap().entrySet().stream().filter(entry -> {
			return (entry.getValue().size() == 0);
		}).map(Map.Entry::getKey).collect(Collectors.toList());
		Log.info(LOGGER, "Unable to Verify: ");
		if (unverifiedList.size() != 0) {
			unverifiedList.forEach(item -> {
				Log.info(LOGGER, "\t" + item);
			});
		} else {
			Log.info(LOGGER, "\t--None--"); 
		}
		List<String> fileList = result.getResultMap().entrySet().stream().filter(entry -> {
			return (entry.getValue().size() != 0);
		}).map(Map.Entry::getValue).flatMap(item -> item.stream()).collect(Collectors.toList());
		Log.info(LOGGER, "Verified files in directory: " + conf.getChecklistForJiraExportDir());
		if (fileList.size() != 0) {
			fileList.forEach(item -> {
				Log.info(LOGGER, "\t" + item);
			});
		} else {
			Log.info(LOGGER, "\t--None--"); 
		}
	}
	
	public static void main(String[] args) throws Exception {
		CommandLine cmd = CLI.parseCommandLine(args);
		if (cmd != null) {
			Config conf = parseConfig(cmd);
			for (Option opt : cmd.getOptions()) {
				CLIOptions cli = CLI.CLIOptions.parse(opt);
				if (cli != null) {
					switch (cli) {
					case EXPORT_FIELD: 
						exportFieldList(conf);
						break;						
					case TRIGGER_EXPORT: {
						List<CustomField> fieldList = readFieldList(cmd.getOptionValue(CLI.FIELD_LIST_OPTION));
						triggerExport(conf, fieldList);
						break;
					}
					case EXPORT_USAGE: {
						List<CustomField> fieldList = readFieldList(cmd.getOptionValue(CLI.FIELD_LIST_OPTION));
						String folder = cmd.getOptionValue(CLI.GZ_DIR_OPTION);
						exportUsage(conf, fieldList, folder);
						break;
					}
					}
				}
			}
		}
	}
}
