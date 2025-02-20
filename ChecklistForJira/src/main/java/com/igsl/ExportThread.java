package com.igsl;

import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URIBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.htmlunit.WebClient;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;

import com.igsl.mybatis.CustomField;

/**
 * Thread to trigger Checklist for Jira export
 * Then monitor the generated files to ensure the export is complete
 */
public class ExportThread implements Callable<ExportResult> {

	private static final Logger LOGGER = LogManager.getLogger();
	public static final SimpleDateFormat SDF = new SimpleDateFormat("yyyyMMdd-HHmmss");

	private Config conf;
	private Path exportDirectory;
	private CustomField customField;
	private long maxWait;
	private List<String> completed = new ArrayList<>();
	private List<String> failed = new ArrayList<>();
	private Map<String, List<String>> bypassMap;
	
	public ExportThread(Config conf, Path exportDirectory, CustomField customField, long maxWait, Map<String, List<String>> bypassMap) {
		this.conf = conf;
		this.exportDirectory = exportDirectory;
		this.customField = customField;
		this.maxWait = maxWait;
		this.bypassMap = bypassMap;
	}
		
	@Override
	public ExportResult call() {
		Log.info(LOGGER, "Export started for " + customField.getFieldName() + " (" + customField.getFieldId() + ")");
		ExportResult result = new ExportResult();
		try {
			List<Callable<ExportResult>> list = triggerExport();
			if (list.size() == 0) {
				// No items found
				return result;
			}			
			// Start monitoring threads
			ExecutorService service = Executors.newFixedThreadPool(list.size());
			List<Future<ExportResult>> futureList = new ArrayList<>();
			list.forEach(thread -> {
				futureList.add(service.submit(thread));
			});
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
						toRemove.add(future);
					}
				}
				futureList.removeAll(toRemove);
			}			
			service.shutdownNow();			
		} catch (Exception ex) {
			Log.error(LOGGER, "Error triggering export for " + customField.getFieldName() + " (" + customField.getFieldId() + ")", ex);
		}
		Log.info(LOGGER, "Export ended for " + customField.getFieldName() + " (" + customField.getFieldId() + ")");
		return result;
	}

	public static URL createURI(Config conf, String path) throws Exception {
		return new URI(conf.getSourceScheme() + "://" + conf.getSourceHost() + path).toURL();
	}
	
	public static void loginJira(Config conf, WebClient client) throws Exception {
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
	
	private List<Callable<ExportResult>> triggerExport() throws Exception {
		List<Callable<ExportResult>> result = new ArrayList<>();
		if (bypassMap != null) {
			Log.info(LOGGER, "Bypass used instead of triggering export");
			if (bypassMap.containsKey(customField.getFieldId())) {
				List<String> list = bypassMap.get(customField.getFieldId());
				for (String id : list) {
					result.add(new FileMonitorThread(exportDirectory, customField, id, maxWait));
				}
			}
		} else {
			// Trigger via web
			try (final WebClient webClient = new WebClient()) {
				webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
				webClient.getOptions().setThrowExceptionOnScriptError(false);
				// Login
				loginJira(conf, webClient);
				// Go to custom field page
				HtmlPage customFieldPage = webClient.getPage(createURI(conf, 
						"/secure/admin/ConfigureCustomField!default.jspa?customFieldId=" + customField.getFieldId()));
				// Find all the contexts
				// Find a.aui-button with content "Export"
				List<Object> buttonList = customFieldPage.getByXPath("//a[@class='aui-button'][text()[contains(., 'Export')]]");
				if (buttonList.size() != 0) {
					for (Object linkElement : buttonList) {
						HtmlElement link = (HtmlElement) linkElement;
						// Grab fieldConfigId off the link
						String href = link.getAttribute("href");
						URIBuilder builder = new URIBuilder(href);
						String fieldConfigId = null;
						for (NameValuePair query : builder.getQueryParams()) {
							if ("fieldConfigId".equals(query.getName())) {
								fieldConfigId = query.getValue();
								break;
							}
						}
						// Click and get export page
						HtmlPage exportPage = link.click();
						result.add(new ExportContextThread(conf, customField, fieldConfigId, exportPage.getUrl(), maxWait));
					}	// For all contexts
				} else {
					Log.info(LOGGER, "No exportable context found");
				}
			} // Try
		}
		return result;
	}

	public List<String> getCompleted() {
		return completed;
	}

	public List<String> getFailed() {
		return failed;
	}

}
