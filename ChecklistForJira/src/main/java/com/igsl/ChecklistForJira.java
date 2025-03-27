package com.igsl;

import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
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
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.igsl.CLI.CLIOptions;
import com.igsl.json.ChecklistForJiraData;
import com.igsl.json.ChecklistItem;
import com.igsl.json.ChecklistTemplate;
import com.igsl.json.JsonChecklistItem;
import com.igsl.mybatis.CustomField;
import com.igsl.mybatis.DataMapper;
import com.igsl.mybatis.IssueType;
import com.igsl.mybatis.Project;
import com.igsl.mybatis.Source;
import com.igsl.mybatis.Workflow;
import com.igsl.postfunction.ChecklistFunction;

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
	private static final ObjectMapper XM = new XmlMapper()
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
	
	private static String getManifestTemplateName(String customFieldName, String contextId) {
		// Context name is most of the time too long
		// So just use context Id instead
		return customFieldName + "-" + contextId;
	}
	
	private static String getWorkflowTemplateName(
			Map<String, CustomField> fieldMap, 
			List<String> customFieldIds, 
			String transitionName, 
			String functionName) {
		StringBuilder customFieldNames = new StringBuilder();
		for (String id : customFieldIds) {
			if (fieldMap.containsKey(id)) {
				customFieldNames.append(",").append(fieldMap.get(id).getFieldName());
			} else {
				customFieldNames.append(",").append(id);
			}
		}
		if (customFieldNames.length() != 0) {
			customFieldNames.delete(0, 1);
		}
		return customFieldNames + "-" + transitionName + "-" + functionName;
	}
	
	private static String getTruncatedTemplateName(String fullName, Set<String> nameList) {
		if (fullName.length() > MAX_TEMPLATE_NAME_LENGTH) {
			fullName = fullName.substring(0, MAX_TEMPLATE_NAME_LENGTH);
		}
		String result = fullName;
		int count = 0;
		while (nameList.contains(result)) {
			count++;
			String suffix = String.format("-%02d", Integer.toString(count));
			if (result.length() + suffix.length() > MAX_TEMPLATE_NAME_LENGTH) {
				result = fullName.substring(0, result.length() - suffix.length()) + suffix;
			} else {
				result = result + suffix;
			}
		}
		nameList.add(result);
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
	
	private static List<ChecklistFunction> parseWorkflowChecklistPostFunctions(String workflowName, String source) throws Exception {
		List<ChecklistFunction> result = new ArrayList<>();
		final String XPATH_CHECKLISTFORJIRA = "//post-functions/function[arg[@name='class.name'][text()='com.okapya.jira.customfields.workflow.functions.ChecklistFunction']]";
		final String XPATH_PARENT_TRANSITION = "./ancestor::action";
		XPathFactory xpathFact = XPathFactory.newInstance();
        XPath xpath = xpathFact.newXPath();
        DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
        docFactory.setValidating(false);
        docFactory.setNamespaceAware(true);
        docFactory.setFeature("http://xml.org/sax/features/namespaces", false);
        docFactory.setFeature("http://xml.org/sax/features/validation", false);
        docFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
        docFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
        Document doc = docBuilder.parse(new InputSource(new StringReader(source)));
        // Locate Checklist for Jira post-functions
        NodeList postFunctions = (NodeList) xpath.evaluate(XPATH_CHECKLISTFORJIRA, doc, XPathConstants.NODESET);
        if (postFunctions != null && postFunctions.getLength() != 0) {
        	for (int i = 0; i < postFunctions.getLength(); i++) {
        		Node postFunction = postFunctions.item(i);
        		Node transition = (Node) xpath.evaluate(XPATH_PARENT_TRANSITION, postFunction, XPathConstants.NODE);
        		ChecklistFunction func = new ChecklistFunction(workflowName, postFunction, transition);
        		result.add(func);
        	}
        }
        return result;
	}
	
	private static void recordTemplateFromGZ(
			CSVPrinter printer, 
			String source,
			Set<String> templateNameList,
			String fieldName, 
			String contextName,
			String templateFullName,
			String templateContent) throws IOException {
		List<String> data = new ArrayList<>();
		data.addAll(Arrays.asList(
				source,
				"N/A", "N/A", "N/A", "N/A", "N/A",
				fieldName, 
				contextName, 
				templateFullName,
				getTruncatedTemplateName(templateFullName, templateNameList)));
		data.addAll(splitChecklistTemplate(templateContent));		
		printer.printRecord(data);
	}

	private static void recordTemplateFromWorkflow(
			CSVPrinter printer, 
			Set<String> templateNameList,
			String workflowName,
			String transitionName,
			String transitionId,
			boolean initialTransition,
			boolean append,
			String fieldName, 
			String contextName,
			String templateFullName,
			String templateContent) throws IOException {
		List<String> data = new ArrayList<>();
		data.addAll(Arrays.asList(
				"Workflow",
				workflowName, 
				transitionName, 
				transitionId,
				Boolean.toString(initialTransition),
				Boolean.toString(append),
				fieldName,
				contextName,
				templateFullName,
				getTruncatedTemplateName(templateFullName, templateNameList)));
		data.addAll(splitChecklistTemplate(templateContent));
		printer.printRecord(data);
	}

	private static void recordTemplateUsage(
			CSVPrinter printer, 
			String source, 
			String projectKey,
			String issueTypes,
			String customFieldName,
			String contextName,
			String templateFullName,
			String templateEmpty) throws IOException {
		List<String> data = Arrays.asList(
				source, 
				projectKey,
				issueTypes,
				customFieldName,
				contextName,
				templateFullName,
				templateEmpty);
		printer.printRecord(data);
	}
	
	private static void processWorkflow(
			Workflow workflow, 
			Map<String, CustomField> fieldMap,
			CSVPrinter template,
			CSVPrinter usage,
			Set<String> projectList,
			Set<String> templateNameList) throws Exception { 
		ObjectReader checklistItemReader = OM.readerFor(ChecklistItem.class);
		Log.info(LOGGER, "Processing workflow [" + workflow.getWorkflowName() + "]");
		List<ChecklistFunction> functions = parseWorkflowChecklistPostFunctions(workflow.getWorkflowName(), workflow.getDescriptor());
		for (ChecklistFunction function : functions) {
			// Convert template
			List<ChecklistItem> items = new ArrayList<>();
			for (String itemString : function.getItems()) {
				// URL decode
				String s = URLDecoder.decode(itemString, Charset.defaultCharset());
				// Parse as JSON
				ChecklistItem item = checklistItemReader.readValue(s);
				items.add(item);
			}
			if (items.size() != 0) {
				Log.info(LOGGER, 
						"Workflow [" + workflow.getWorkflowName() + "] " + 
						"Transition [" + function.getTransitionName() + " (" + function.getTransitionId() + ")] contains checklist template");
				// Record template
				String convertedChecklist = ChecklistItem.convert(items);
				Log.debug(LOGGER, 
						"Workflow [" + workflow.getWorkflowName() + "] " + 
						"Transition [" + function.getTransitionName() + " (" + function.getTransitionId() + ")] contains checklist template [" + 
						convertedChecklist + "]");
				String templateFullName = getWorkflowTemplateName(
							fieldMap, 
							function.getSelectedCustomFieldIdList(),
							function.getTransitionName(),
							function.getFunctionName());
				recordTemplateFromWorkflow(
						template, 
						templateNameList, 
						workflow.getWorkflowName(), 
						function.getTransitionName(),
						function.getTransitionId(),
						function.isInitialTransition(),
						function.isAppendOperation(),
						function.getSelectedCustomFieldIdNames(fieldMap),
						"N/A",
						templateFullName,
						convertedChecklist);
				// Record usage
				Log.info(LOGGER, "Associated projects: " + workflow.getProjectList().size());
				for (Project p : workflow.getProjectList()) {
					projectList.add(p.getProjectKey());
					for (String fieldId : function.getSelectedCustomFieldIdList()) {
						String fieldName = fieldId;
						if (fieldMap.containsKey(fieldId)) {
							fieldName = fieldMap.get(fieldId).getFieldName();
						}
						Log.debug(LOGGER, 
								"Workflow [" + workflow.getWorkflowName() + "] " + 
								"Transition [" + function.getTransitionName() + " (" + function.getTransitionId() + ")] " + 
								"Associated with project [" + p.getProjectKey() + "] " + 
								"Issue types [" + p.getIssueTypeString() + "] " + 
								"Field [" + fieldName + "] " + 
								"Template Name [" + templateFullName + "]");
						recordTemplateUsage(
								usage, 
								"Workflow",
								p.getProjectKey(),
								p.getIssueTypeString(),
								fieldName,
								"N/A",
								templateFullName,
								Boolean.toString(convertedChecklist.length() == 0));
					}
				}
			} else {
				Log.info(LOGGER, "Workflow [" + workflow.getWorkflowName() + "] contains no checklist templates");
			}
		} // For each post-function
	}
	
	private static void exportUsage(
			Config conf, 
			List<CustomField> fieldList, 
			String wfFile, 
			String gzFolder) throws Exception {
		Map<String, CustomField> fieldMap = fieldList.stream().collect(
				Collectors.toMap(CustomField::getFieldId, item -> item));
		// Output to CSV
		Date now = new Date();
		Path gzDir = Paths.get(gzFolder);
		Path extractDir = Paths.get("GZ." + SDF.format(now));
		Path csvProject = Paths.get("ChecklistProject." + SDF.format(now) + ".csv"); 
		Path csvTemplate = Paths.get("ChecklistTemplate." + SDF.format(now) + ".csv"); 
		Path csvUsage = Paths.get("ChecklistUsage." + SDF.format(now) + ".csv"); 
		CSVFormat fmtTemplate = CSV.getCSVWriteFormat(Arrays.asList(
					"Template Source",
					"Workflow Name",
					"Transition Name",
					"Transition ID",
					"Initial Transition",
					"Append",
					"Field Name",
					"Context Name",
					"Template Full Name",
					"Template Truncated Name",
					"Template Content"
				));
		CSVFormat fmtUsage = CSV.getCSVWriteFormat(Arrays.asList(
					"Source",
					"Project Key",
					"Issue Type(s)",
					"Field Name",
					"Context Name", 
					"Template Full Name",
					"Template Empty"
				));
		CSVFormat fmtProject = CSV.getCSVWriteFormat(Arrays.asList(
					"Project Key"
				));
		try {
			Files.createDirectory(extractDir);
			try (	FileWriter fwProject = new FileWriter(csvProject.toFile());
					CSVPrinter project = new CSVPrinter(fwProject, fmtProject);					
					FileWriter fwTemplate = new FileWriter(csvTemplate.toFile());
					CSVPrinter template = new CSVPrinter(fwTemplate, fmtTemplate);
					FileWriter fwUsage = new FileWriter(csvUsage.toFile()); 
					CSVPrinter usage = new CSVPrinter(fwUsage, fmtUsage)) {

				// Projects that uses Checklist for Jira
				Set<String> projectList = new HashSet<>();
				Set<String> templateNameList = new HashSet<>();

				// Read workflows
				Log.info(LOGGER, "Processing workflows");
				if (wfFile != null) {
					// From file
					Log.info(LOGGER, "Reading workflow from file: " + wfFile);
					ObjectReader reader = OM.readerFor(new TypeReference<List<Workflow>>() {});
					List<Workflow> workflows = reader.readValue(Paths.get(wfFile).toFile());
					for (Workflow workflow : workflows) {
						try {
							processWorkflow(workflow, fieldMap, template, usage, projectList, templateNameList);						
						} catch (Exception ioex) {
							Log.error(LOGGER, "Error processing " + workflow.getWorkflowName(), ioex);
						}
					};
				} else {
					// From DB
					Log.info(LOGGER, "Reading workflow from database");
					SqlSessionFactory factory = setupMyBatis(conf);
					try (SqlSession session = factory.openSession()) {
						// Get filter info from source
						DataMapper mapper = session.getMapper(DataMapper.class);
						List<Workflow> workflows = mapper.getWorkflows();
						for (Workflow workflow : workflows) {
							try {
								processWorkflow(workflow, fieldMap, template, usage, projectList, templateNameList);
							} catch (Exception ioex) {
								Log.error(LOGGER, "Error processing " + workflow.getWorkflowName(), ioex);
							}
						} // For each workflow
					} // SqlSession resource
				} // If wfFolder
				
				Log.info(LOGGER, "Processing GZ from [" + gzFolder + "]");
				// Group the files in gzDir by fieldId-context
				Map<String, Map<String, List<Path>>> map = new HashMap<>();
				DirectoryStream<Path> gzDirStream = Files.newDirectoryStream(gzDir, "*.gz");
				Pattern gzPattern = Pattern.compile("customfield_([0-9]+)-([0-9]+)-([0-9]+)\\.gz");
				gzDirStream.forEach(p -> {
					Matcher m = gzPattern.matcher(p.getFileName().toString());
					if (m.matches()) {
						String fieldId = m.group(1);
						String contextId = m.group(2);
						if (!map.containsKey(fieldId)) {
							map.put(fieldId, new HashMap<>());
						}
						Map<String, List<Path>> submap = map.get(fieldId);
						if (!submap.containsKey(contextId)) {
							submap.put(contextId, new ArrayList<>());
						}
						submap.get(contextId).add(p);
					}
				});
				
				ObjectReader reader = OM.readerFor(ChecklistForJiraData.class);
				// For each field
				for (Map.Entry<String, Map<String, List<Path>>> customFieldEntry : map.entrySet()) {
					String customFieldId = customFieldEntry.getKey();
					String customFieldName = fieldMap.get(customFieldId).getFieldName();
					for (Map.Entry<String, List<Path>> contextEntry : customFieldEntry.getValue().entrySet()) {
						// Handle manifest and template types
						String contextId = "";
						String contextName = "";
						for (Path file : contextEntry.getValue()) {
							Log.info(LOGGER, "Processing GZip file [" + file + "]");
							Path extractedFile = gunzip(extractDir, file);
							ChecklistForJiraData data = null;
							try (FileInputStream in = new FileInputStream(extractedFile.toFile())) {
								Log.info(LOGGER, "Processing extracted file [" + extractedFile + "]");
								data = reader.readValue(in);
								switch(data.getType()) {
								case ChecklistForJiraData.TYPE_MANIFEST:
									Log.info(LOGGER, "Processing manifest [" + extractedFile + "]");
									//String customFieldId = String.valueOf(data.getFieldConfig().getCustomFieldId());
									//String customFieldName = data.getFieldConfig().getCustomFieldName();
									contextId = String.valueOf(data.getFieldConfig().getId());
									contextName = data.getFieldConfig().getName();
									String fullName = getManifestTemplateName(customFieldName, contextId);
									List<ChecklistItem> checklistItems = new ArrayList<>();
									// Global items
									if (data.getGlobalItems() != null) {
										checklistItems.addAll(data.getGlobalItems());
									}
									// Default local items
									if (data.getDefaultLocalItems() != null) {
										checklistItems.addAll(data.getDefaultLocalItems());
									}
									// Convert checklistItems to new format
									String convertedChecklist = ChecklistItem.convert(checklistItems);
									// Find accurate mapping from template to projects
									if (!fieldMap.containsKey(customFieldId)) {
										// Custom field not found, sound alarm
										Log.error(LOGGER, 
												"Checklist field " + customFieldName + " (" + customFieldId + ") not found, manifest ignored");
										break;
									}
									recordTemplateFromGZ(
											template, 
											"Manifest", 
											templateNameList, 
											customFieldName, 
											contextName,
											fullName, 
											convertedChecklist);
									Log.info(LOGGER, "Template [" + fullName + "] processed");
									// Record usage using GZ data
									StringBuilder issueTypes = new StringBuilder();
									for (String issueType : data.getFieldConfig().getIssueTypes().values()) {
										issueTypes.append("\n").append(issueType);
									}
									if (issueTypes.length() != 0) {
										issueTypes.delete(0, 1);
									} else if (issueTypes.length() == 0) {
										issueTypes.append(IssueType.ALL_ISSUE_TYPE_NAME);
									}
									if (data.getFieldConfig().getProjects().values().size() == 0) {
										recordTemplateUsage(
												usage, 
												"GZip", 
												"All", 
												issueTypes.toString(), 
												customFieldName, 
												contextName, 
												fullName, 
												Boolean.toString(convertedChecklist.length() == 0));
									} else {
										for (String projectKey : data.getFieldConfig().getProjects().values()) {
											projectList.add(projectKey);
											Log.debug(LOGGER, 
													"Checklist template [" + fullName + "] " + 
													"Associated with project [" + projectKey + "] " + 
													"Issue types [" + issueTypes.toString() + "] " + 
													"Custom field [" + customFieldName + "] ");
											recordTemplateUsage(
													usage, 
													"GZip", 
													projectKey, 
													issueTypes.toString(), 
													customFieldName, 
													contextName, 
													fullName, 
													Boolean.toString(convertedChecklist.length() == 0));
										}
									}
									Log.info(LOGGER, 
											"Template [" + fullName + "] is associated via GZ with " + 
											data.getFieldConfig().getProjects().values().size() + " project(s)");
									break;
								case ChecklistForJiraData.TYPE_TEMPLATE: 
									Log.info(LOGGER, "Processing templates [" + extractedFile + "]");
									// Templates as new checklists with no usage
									if (data.getTemplates() != null) {
										for (ChecklistTemplate definedTemplate : data.getTemplates()) {
											String convertedDefinedChecklist = ChecklistItem.convert(definedTemplate.getItems());
											Log.info(LOGGER, "Template [" + definedTemplate.getName() + "] found");
											recordTemplateFromGZ(
													template, 
													"Templates", 
													templateNameList, 
													customFieldName,
													contextName,
													definedTemplate.getName(), 
													convertedDefinedChecklist);
										}
									}
									break;
								default:
									Log.warn(LOGGER, "Type: [" + data.getType() + "] in [" + extractedFile + "] ignored");
									break;
								}
							} catch (Exception ex) {
								Log.error(LOGGER, "Error processing " + extractedFile + ", file ignored", ex);
							}
						}	// For each file
					}	// For context
					// Record usage using field data
					CustomField cf = fieldMap.get(customFieldId);
					String fullName = customFieldName;
					Set<String> projectKeySet = new HashSet<>();
					for (Source source : cf.getSourceList()) {
						for (Project p : source.getProjectList()) {
							projectKeySet.add(p.getProjectKey());
							projectList.add(p.getProjectKey());
							recordTemplateUsage(
									usage, 
									source.getSource(), 
									p.getProjectKey(), 
									p.getIssueTypeString(),
									cf.getFieldName(), 
									"N/A", 
									"N/A", 
									"N/A");
							Log.info(LOGGER, 
									"Template [" + fullName + "] is associated via workflow/screens with " + 
									p.getProjectKey() + " issue types [" + p.getIssueTypeString() + "]");
						}
					}
					Log.info(LOGGER, 
							"Template [" + fullName + "] is associated via workflow/screen with " + 
							projectKeySet.size() + " project(s)");
				}	// For each custom field
				// Write project list
				for (String pKey : projectList) {
					project.printRecord(pKey);
				}				
			} // Try file outputs
		} finally {
			FileUtils.deleteDirectory(extractDir.toFile());
		}
		Log.info(LOGGER, "Checklist for Jira templates written to: " + csvTemplate.toString());
		Log.info(LOGGER, "Template usage written to: " + csvUsage.toString());
	}
	
	private static void exportWorkflows(Config conf) throws Exception {
		try {
			SqlSessionFactory factory = setupMyBatis(conf);
			try (SqlSession session = factory.openSession()) {
				DataMapper mapper = session.getMapper(DataMapper.class);
				List<Workflow> workflows = mapper.getWorkflows();
				Path info = Paths.get("Workflows." + SDF.format(new Date()) + ".json");
				OM.writeValue(info.toFile(), workflows);
				Log.info(LOGGER, "Workflows exported to: " + info.toAbsolutePath().toString());
			}
		} catch (IOException ioex) {
			Log.error(LOGGER, "Failed to create output directory for workflow", ioex);
		}
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
	
	private static Map<String, List<String>> readBypassFile(Path bypassFile) {
		Map<String, List<String>> result = new HashMap<>();
		try (	FileReader fr = CSV.getCSVFileReader(bypassFile); 
				CSVParser p = new CSVParser(fr, CSV.getCSVReadFormat())) {
			p.forEach(record -> {
				String customFieldId = record.get(0);
				String contextId = record.get(1);
				if (!result.containsKey(customFieldId)) {
					result.put(customFieldId, new ArrayList<>());
				}
				result.get(customFieldId).add(contextId);
			});
		} catch (IOException ioex) {
			Log.error(LOGGER, "Error reading bypass CSV", ioex);
		}
		return result;
	}
	
	private static void triggerExport(Config conf, List<CustomField> fieldList, Path bypassFile) {
		Instant startTime = Instant.now();
		ExportResult result = new ExportResult();
		Map<String, List<String>> bypassMap = null;
		if (bypassFile != null) {
			bypassMap = readBypassFile(bypassFile);
		}
		ExecutorService service = Executors.newFixedThreadPool(conf.getConcurrentExportCount());
		List<Future<ExportResult>> futureList = new ArrayList<>();
		for (CustomField field : fieldList) {
			futureList.add(service.submit(new ExportThread(
					conf, Paths.get(conf.getChecklistForJiraExportDir()), field, conf.getExportMaxWaitMS(), bypassMap)));
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
		Instant endTime = Instant.now();
		// Print result
		// Verified
		List<String> verifiedList = result.getResultMap().entrySet().stream().filter(entry -> {
			return (entry.getValue().getFiles().size() != 0);
		}).map(Map.Entry::getKey).collect(Collectors.toList());
		verifiedList.sort(Comparator.naturalOrder());
		Log.info(LOGGER, "Verified: ");
		if (verifiedList.size() != 0) {
			verifiedList.forEach(item -> {
				Log.info(LOGGER, "\t" + item);
			});
		} else {
			Log.info(LOGGER, "\t--None--"); 
		}		

		List<Map.Entry<String, ExportResult.ResultItem>> unverifiedList = result.getResultMap().entrySet().stream().filter(entry -> {
			return (entry.getValue().getFiles().size() == 0);
		}).collect(Collectors.toList());
		Log.info(LOGGER, "Unable to Verify: ");
		if (unverifiedList.size() != 0) {
			unverifiedList.forEach(item -> {
				Log.info(LOGGER, "\t" + item.getKey() + " Error: " + item.getValue().getErrorMessage());
			});
		} else {
			Log.info(LOGGER, "\t--None--"); 
		}
		List<String> fileList = result.getResultMap().entrySet().stream().filter(entry -> {
			return (entry.getValue().getFiles().size() != 0);
		}).map(Map.Entry::getValue).flatMap(item -> item.getFiles().stream()).collect(Collectors.toList());
		fileList.sort(Comparator.naturalOrder());
		Log.info(LOGGER, "Verified files in directory: " + conf.getChecklistForJiraExportDir());
		if (fileList.size() != 0) {
			fileList.forEach(item -> {
				Log.info(LOGGER, "\t" + item);
			});
		} else {
			Log.info(LOGGER, "\t--None--"); 
		}
		Duration elapsed = Duration.between(
				LocalTime.from(startTime.atZone(ZoneId.systemDefault())), 
				LocalTime.from(endTime.atZone(ZoneId.systemDefault())));
		Log.info(LOGGER, "Elapsed: " + 
				elapsed.toDaysPart() + " day(s) " + 
				elapsed.toHoursPart() + " hour(s) " + 
				elapsed.toMinutesPart() + " minute(s) " + 
				elapsed.toSecondsPart() + " second(s) " + 
				elapsed.toMillisPart() + " millisecond(s) ");	
	}
	
	private static void convertCSV(Config conf, String csvFile) {
		final String NEWLINE = "\r\n";
		ObjectMapper strictOM = new ObjectMapper();
		ObjectReader reader = strictOM.readerFor(new TypeReference<List<JsonChecklistItem>>(){});
		CSVFormat csvReadFormat = CSVFormat.Builder.create()
				.setHeader("Rule", "Name", "Template")
				.setSkipHeaderRecord(false)
				.build();
		CSVFormat csvWriteFormat = CSVFormat.Builder.create()
				.setHeader("Rule", "Name", "Template", "Translated")
				.setSkipHeaderRecord(false)
				.build();
		Path inputFile = Paths.get(csvFile);
		Path outputFile = inputFile.getParent().resolve("Translated." + inputFile.getFileName().toString());
		try (	FileReader fr = new FileReader(inputFile.toFile()); 
				CSVParser csvParser = new CSVParser(fr, csvReadFormat); 
				FileWriter fw = new FileWriter(outputFile.toFile());
				CSVPrinter csvPrinter = new CSVPrinter(fw, csvWriteFormat)) {
			csvParser.forEach(row -> {
				try {
					StringBuilder sb = new StringBuilder();
					String template = row.get(2);
					Log.info(LOGGER, template);
					List<JsonChecklistItem> checklist = reader.readValue(template);
					for (JsonChecklistItem item : checklist) {
						sb.append(NEWLINE);
						if (item.isMandatory()) {
							sb.append("[*]");
						} else {
							sb.append("[ ]");
						}
						sb.append(item.getName());
					}
					if (sb.length() != 0) {
						sb.delete(0, 2);
					}
					csvPrinter.printRecord(row.get(0), row.get(1), row.get(2), sb.toString());
				} catch (IOException ex) {
					Log.error(LOGGER, "Error", ex);
				}
			});
		} catch (Exception ex) {
			Log.error(LOGGER, "Error", ex);
		}
	}
	
	public static void main(String[] args) throws Exception {
		CommandLine cmd = CLI.parseCommandLine(args);
		if (cmd != null) {
			StringBuilder cmdString = new StringBuilder();
			for (String s : args) {
				cmdString.append(s).append(" ");
			}
			Log.info(LOGGER, "Commandline: [" + cmdString.toString() + "]");
			Config conf = parseConfig(cmd);
			for (Option opt : cmd.getOptions()) {
				CLIOptions cli = CLI.CLIOptions.parse(opt);
				if (cli != null) {
					switch (cli) {
					case CONVERT_CSV:
						String jsonFile = cmd.getOptionValue(CLI.CONVERT_CSV_OPTION);
						convertCSV(conf, jsonFile);
						break;
					case EXPORT_WORKFLOW:
						exportWorkflows(conf);
						break;
					case EXPORT_FIELD: 
						exportFieldList(conf);
						break;						
					case TRIGGER_EXPORT: {
						List<CustomField> fieldList = readFieldList(cmd.getOptionValue(CLI.FIELD_LIST_OPTION));
						String bypassFile = cmd.getOptionValue(CLI.BYPASS_TRIGGER_OPTION);
						Path bypassPath = null;
						if (bypassFile != null) {
							bypassPath = Paths.get(bypassFile);
						}
						triggerExport(conf, fieldList, bypassPath);
						break;
					}
					case EXPORT_USAGE: {
						List<CustomField> fieldList = readFieldList(cmd.getOptionValue(CLI.FIELD_LIST_OPTION));
						String gzFolder = cmd.getOptionValue(CLI.GZ_DIR_OPTION);
						String wfFolder = cmd.getOptionValue(CLI.WF_FILE_OPTION);
						exportUsage(conf, fieldList, wfFolder, gzFolder);
						break;
					}
					}
				}
			}
		}
	}
}
