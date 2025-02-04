package com.igsl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.igsl.json.ChecklistForJiraData;
import com.igsl.mybatis.CustomField;

public class MonitorThread implements Callable<ExportResult> {

	private static ObjectMapper OM = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
	private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyyMMdd-HHmmss");
	private static final Logger LOGGER = LogManager.getLogger();
	private static final long WAIT = 1000;

	private Path exportDirectory;
	private CustomField customField;
	private String contextId;
	private long maxWait;

	public MonitorThread(Path exportDirectory, CustomField customField, String contextId, long maxWait) {
		this.exportDirectory = exportDirectory;
		this.customField = customField;
		this.contextId = contextId;
		this.maxWait = maxWait;
	}
	
	@Override
	public ExportResult call() {
		Log.info(LOGGER, "Verification started for " + getName());
		ExportResult result = new ExportResult();
		Path extractDir = Paths.get(getName());
		boolean completed = false;
		boolean manifestFound = false;
		long issueCount = 0;
		boolean valueFound = false;
		List<String> filesFound = new ArrayList<>();
		try {
			extractDir = Files.createDirectory(extractDir);
			ObjectReader reader = OM.readerFor(ChecklistForJiraData.class);
			long wait = 0;
			while (true) {
				if (wait > maxWait) {
					break;
				}
				// Find GZ files
				File[] fileList = exportDirectory.toFile().listFiles((dir, name) -> 
					name.toLowerCase().startsWith("customfield_" + customField.getFieldId() + "-" + contextId + "-") && 
					name.toLowerCase().endsWith(".gz"));
				// Parse content
				if (fileList != null) {
					for (File gzFile : fileList) {
						try {
							Path file = ChecklistForJira.gunzip(extractDir, gzFile.toPath());
							ChecklistForJiraData data = reader.readValue(file.toFile());
							filesFound.add(FilenameUtils.getName(gzFile.toString()));
							switch(data.getType()) {
							case "manifest": 
								manifestFound = true;
								issueCount = data.getIssueCount();
								break;
							case "values": 
								valueFound = true;
								break;
							default: 
								break;
							}
							// If manifest type, get issue count
							// If issue count not 0, look for value type
						} catch (Exception ex) {
							Log.error(LOGGER, "Error processing " + gzFile, ex);
						}
					}
				}
				if (manifestFound && (issueCount == 0 || valueFound)) {
					// Completed
					completed = true;
					break;
				} else {
					try {
						Thread.sleep(WAIT);
					} catch (InterruptedException iex) {
						Log.error(LOGGER, "Sleep interrupted", iex);
					}
				}
			}
			if (completed) {
				filesFound.forEach(file -> {
					result.addItem(customField, contextId, file);
				});
			} else {
				result.addItem(customField, contextId, null);
			}
		} catch (IOException ioex) {
			Log.error(LOGGER, "Failed to create temp directory", ioex);
		} finally {
			try {
				FileUtils.deleteDirectory(extractDir.toFile());
			} catch (IOException ioex) {
				Log.error(LOGGER, "Failed to delete temp directory", ioex);
			}
		}
		Log.info(LOGGER, "Verification ended for " + getName() + ": " + completed);
		return result;
	}

	public String getName() {
		return customField.getFieldName() + " (customfield_" + customField.getFieldId() + ") - " + contextId;
	}
	
}
