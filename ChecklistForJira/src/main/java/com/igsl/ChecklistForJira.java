package com.igsl;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URIBuilder;
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
import org.htmlunit.HttpMethod;
import org.htmlunit.WebClient;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
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
	private static final int MAX_COL_SIZE = 32767;	// Max col data size in a cell supported by Excel
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
	
	private static URL createURI(Config conf, String path) throws Exception {
		return new URI(conf.getSourceScheme() + "://" + conf.getSourceHost() + path).toURL();
	}
	
	private static void loginJira(Config conf, WebClient client) throws Exception {
		// Login
		HtmlPage loginPage = client.getPage(createURI(conf, "/login.jsp").toString());
		for (HtmlForm form : loginPage.getForms()) {
			if ("login-form".equals(form.getId())) {
				HtmlElement user = (HtmlElement) form.getFirstByXPath("//input[@id='login-form-username']");
				HtmlElement password = (HtmlElement) form.getFirstByXPath("//input[@id='login-form-password']");
				HtmlElement button = (HtmlElement) form.getFirstByXPath("//input[@id='login-form-submit']");
				if (user != null && password != null && button != null) {
					user.type(conf.getSourceUser());
					password.type(conf.getSourcePassword());
					final HtmlPage landingPage = button.click();
					if (landingPage.getUrl().toString().contains("/secure/")) {
						Log.info(LOGGER, "Login successful");
						HtmlElement logoutLink = (HtmlElement) landingPage.getFirstByXPath("//a[@id='log_out']");
						String atlToken = null;
						URIBuilder builder = new URIBuilder(logoutLink.getAttribute("href"));
						for (NameValuePair query : builder.getQueryParams()) {
							if (query.getName().equals("atl_token")) {
								atlToken = query.getValue();
								break;
							}
						}
						// Admin login by adding a form
						DomElement adminButton = landingPage.createElement("button");
						adminButton.setAttribute("type", "submit");
						DomElement atl_token = landingPage.createElement("input");
						atl_token.setAttribute("name", "atl_token");
						atl_token.setAttribute("value", atlToken);
						DomElement webSudoIsPost = landingPage.createElement("input");
						webSudoIsPost.setAttribute("name", "webSudoIsPost");
						webSudoIsPost.setAttribute("value", "false");
						DomElement webSudoDestination = landingPage.createElement("input");
						webSudoDestination.setAttribute("name", "webSudoDestination");
						webSudoDestination.setAttribute("value", "/secure/admin/ViewIssueTypes.jspa");
						DomElement webSudoPassword = landingPage.createElement("input");
						webSudoPassword.setAttribute("name", "webSudoPassword");
						webSudoPassword.setAttribute("value", conf.getSourcePassword());
						DomElement adminForm = landingPage.createElement("form");
						adminForm.setAttribute("method", "POST");
						adminForm.setAttribute("action", "/secure/admin/WebSudoAuthenticate.jspa");
						adminForm.appendChild(adminButton);
						adminForm.appendChild(atl_token);
						adminForm.appendChild(webSudoIsPost);
						adminForm.appendChild(webSudoDestination);
						adminForm.appendChild(webSudoPassword);
						// submit the form
						HtmlPage adminPage = adminButton.click();
						HtmlElement issueTypeHeader = (HtmlElement) adminPage.getFirstByXPath(
								"//div[@class='aui-page-header-main']/h2[normalize-space() = 'Issue types']");
						if (issueTypeHeader == null) {
							throw new Exception("Admin login failed");
						}
						Log.info(LOGGER, "Admin login successful");	
					} else {
						throw new Exception("Login failed, login form does not work");
					}
				} else {
					Log.error(LOGGER, "Login form fields not found");
				}
				break;
			}	
		}	// Form check
	}
	
	private static void triggerExport(Config conf, Collection<CustomField> fields) throws Exception {
		try (final WebClient webClient = new WebClient()) {
			webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
			webClient.getOptions().setThrowExceptionOnScriptError(false);
			// Login
			loginJira(conf, webClient);
			// Go to custom field page
			List<String> expectedFiles = new ArrayList<>();
			for (CustomField cf : fields) {
				HtmlPage customFieldPage = webClient.getPage(createURI(conf, 
						"/secure/admin/ConfigureCustomField!default.jspa?customFieldId=" + cf.getFieldId()).toString());
				// For all custom field contexts links
				for (Object linkElement : customFieldPage.getByXPath(
						"//a[contains(@class, 'config')]")) {
					HtmlElement link = (HtmlElement) linkElement;
					String href = link.getAttribute("href");
					URIBuilder builder = new URIBuilder(href);
					String fieldConfigSchemeId = null;
					String atlToken = null;
					for (NameValuePair query : builder.getQueryParams()) {
						if ("fieldConfigSchemeId".equals(query.getName())) {
							fieldConfigSchemeId = query.getValue();
							continue;
						}
						if ("atl_token".equals(query.getName())) {
							atlToken = query.getValue();
							continue;
						}
					}
					if (atlToken != null && fieldConfigSchemeId != null) {
						// Trigger export
						WebRequest triggerExport = new WebRequest(
								  createURI(conf, "/secure/admin/ExportChecklist!Export.jspa"), HttpMethod.POST);
						triggerExport.setRequestParameters(new ArrayList<org.htmlunit.util.NameValuePair>());
						triggerExport.getRequestParameters().add(
								new org.htmlunit.util.NameValuePair("atl_token", atlToken));
						triggerExport.getRequestParameters().add(
								new org.htmlunit.util.NameValuePair("fieldConfigId", fieldConfigSchemeId));
						webClient.getPage(triggerExport);
						Log.info(LOGGER, "Export triggered for: " + 
								"Custom field: [" + cf.getFieldName() + "] (" + cf.getFieldId() + ") " + 
								"Context: " + fieldConfigSchemeId);
						expectedFiles.add("customfield_" + cf.getFieldId() + "-" + fieldConfigSchemeId + "-[#].gz");
					}
				}	// For all links
			}	// For all fields
			Log.info(LOGGER, "Please wait until export is complete.");
			Log.info(LOGGER, ".gz files will generated in [Jira's data folder]/export/checklist.");
			Log.info(LOGGER, "You should see the following file(s): ");
			for (String s : expectedFiles) {
				Log.info(LOGGER, s);
			}
			Log.info(LOGGER, "There should be at least " + expectedFiles.size() + " file(s)");
		} // Try
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
				// Unzip GZ files
				File[] gzList = gzDir.toFile().listFiles((dir, name) -> {
					return name.toLowerCase().endsWith(".gz");
				});
				for (File gzFile : gzList) {
					String baseName = FilenameUtils.removeExtension(gzFile.getName());
					Path out = extractDir.resolve(baseName);
					try (	GZIPInputStream gin = new GZIPInputStream(new FileInputStream(gzFile));
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
					} catch (Exception e) {
						Log.error(LOGGER, "Error processing " + gzFile.toString() + ", file ignored", e);
					}
				}
				// Process all files
				ObjectReader reader = OM.readerFor(ChecklistForJiraData.class);
				File[] extractedList = extractDir.toFile().listFiles();
				// First pass to count no. of contexts for each custom field
				Map<String, List<String>> fieldToContextMap = new HashMap<>();
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
					}				
				}
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
						// If data (context) specifies 0 projects
						// use data from field map (screen/workflow) instead
						if (data.getFieldConfig().getProjects().size() == 0) {
							for (IssueType it : cf.getIssueTypeList()) {
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
							for (Project p : cf.getProjectList()) {
								projectList.add(p.getProjectKey());
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
										"Template [" + templateName + "] is associated with " + 
										p.getProjectKey());
							}
							Log.info(LOGGER, 
									"Template [" + templateName + "] is associated with " + 
									cf.getProjectList().size() + " project(s)");
						} else {
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
										"Template [" + templateName + "] is associated with " + 
										p);
							}
							Log.info(LOGGER, 
									"Template [" + templateName + "] is associated with " + 
									data.getFieldConfig().getProjects().values().size() + " project(s)");
						}
					}	// If manifest
				} // For all extracted files
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
