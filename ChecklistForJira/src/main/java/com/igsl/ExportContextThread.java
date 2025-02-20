package com.igsl;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.htmlunit.WebClient;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomText;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;

import com.igsl.mybatis.CustomField;

public class ExportContextThread implements Callable<ExportResult> {
	
	private static final Logger LOGGER = LogManager.getLogger();
	private static final long WAIT = 1000;
	
	private static final String COMPLETED_MESSAGE = "Export Finished";
	
	private Config conf;
	private CustomField customField;
	private String contextId;
	private URL url;	// The status tracking page after clicking Export, /secure/admin/admin/ExportChecklist!Progress.jspa?fieldConfigId=?&expectingResult=true
	private long maxWait;
	
	public ExportContextThread(Config conf, CustomField customField, String contextId, URL url, long maxWait) {
		this.conf = conf;
		this.customField = customField;
		this.contextId = contextId;
		this.url = url;
		this.maxWait = maxWait;
	}
	
	public String getName() {
		return "customfield_" + customField.getFieldId() + "-" + contextId;
	}
	
	@Override
	public ExportResult call() throws Exception {
		Instant startTime = Instant.now();
		Log.info(LOGGER, "Verification started for " + getName() + " URL: " + url);
		ExportResult result = new ExportResult();
		try (final WebClient webClient = new WebClient()) {
			webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
			webClient.getOptions().setThrowExceptionOnScriptError(false);
			// Login
			ExportThread.loginJira(conf, webClient);			
			boolean completed = false;
			HtmlPage page = webClient.getPage(url);
			List<Object> exportButtonList = page.getByXPath("//input[@class='aui-button'][@value='Export']");
			if (exportButtonList.size() == 1) {
				HtmlElement button = (HtmlElement) exportButtonList.get(0);
				if (DomElement.ATTRIBUTE_NOT_DEFINED == button.getAttribute("disabled")) {
					HtmlPage resultPage = button.click();
					Log.info(LOGGER, "Export triggered for: " + 
							"Custom field: [" + customField.getFieldName() + "] (" + customField.getFieldId() + ") " + 
							"Context: " + contextId + " " + 
							"Max wait: " + maxWait);
					long wait = 0;
					while (true) {
						if (wait >= maxWait) {
							Log.error(LOGGER, "Timeout waiting for " + 
									"Custom field: [" + customField.getFieldName() + "] (" + customField.getFieldId() + ") " + 
									"Context: " + contextId + " " + 
									"Max wait: " + maxWait + " " + 
									"Waited: " + wait);
							break;
						}
						resultPage = (HtmlPage) resultPage.refresh();
						List<Object> messages = resultPage.getByXPath(""
								+ "//main[@role='main'][./h2[@class='formtitle'][text()='Checklist Export']]/div[contains(@class,'aui-message')]/p[@class='title']/text()");
						if (messages.size() == 1) {
							DomText element = (DomText) messages.get(0);
							String message = element.getTextContent();
							Log.debug(LOGGER, "Message: " + message);
							if (COMPLETED_MESSAGE.equals(message)) {
								result.addItem(customField, contextId, null,  
										"customfield_" + customField.getFieldId() + "-" + contextId + "-*.gz");
								Log.info(LOGGER, "Export completed for " + 
										"Custom field: [" + customField.getFieldName() + "] (" + customField.getFieldId() + ") " + 
										"Context: " + contextId);
								completed = true;
								break;
							}
						} else {
							Log.error(LOGGER, "Message cannot be found");
						}
						try {
							Thread.sleep(WAIT);
						} catch (InterruptedException iex) {
							Log.error(LOGGER, "Sleep interrupted", iex);
						}
					}		
				} else {
					Log.error(LOGGER, 
							"Exported GZ files already exist for " + 
							"Custom field: [" + customField.getFieldName() + "] (" + customField.getFieldId() + ") " + 
							"Context: " + contextId + 
							", please delete them from export directory first");
					result.addItem(customField, contextId, "Exported GZ files already exists in export directory", null);
				}
				Log.info(LOGGER, "Verification ended for " + getName() + ": " + completed);
			} else {
				Log.info(LOGGER, "Export button not found");
				result.addItem(customField, contextId, "Export button not found", null);
			}
		} catch (Exception ex) {
			Log.error(LOGGER, "Exception for " + 
					"Custom field: [" + customField.getFieldName() + "] (" + customField.getFieldId() + ") " + 
					"Context: " + contextId,
					ex);
			result.addItem(customField, contextId, ex.getMessage(), null);
		}
		Instant endTime = Instant.now();
		Duration elapsed = Duration.between(
				LocalTime.from(startTime.atZone(ZoneId.systemDefault())), 
				LocalTime.from(endTime.atZone(ZoneId.systemDefault())));
		Log.info(LOGGER, "Elapsed: " + 
				elapsed.toDaysPart() + " day(s) " + 
				elapsed.toHoursPart() + " hour(s) " + 
				elapsed.toMinutesPart() + " minute(s) " + 
				elapsed.toSecondsPart() + " second(s) " + 
				elapsed.toMillisPart() + " millisecond(s) ");	
		return result;
	}

}
