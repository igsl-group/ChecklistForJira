package com.igsl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
		Pattern gzFileNamePattern = Pattern.compile("customfield_" + customField.getFieldId() + "-" + contextId + "-([0-9])\\.gz");
		ExportResult result = new ExportResult();
		Path extractDir = Paths.get(getName());
		boolean completed = false;
		int lastProcessedIndex = 0;
		boolean manifestFound = false;
		long issueCount = 0;
		long valueCount = 0;
		List<String> filesFound = new ArrayList<>();
		try {
			extractDir = Files.createDirectory(extractDir);
			ObjectReader reader = OM.readerFor(ChecklistForJiraData.class);
			long wait = 0;
			while (true) {
				if (wait > maxWait) {
					Log.error(LOGGER, "Timeout waiting for GZ files");
					break;
				}
				// Find GZ files
				File[] fileList = exportDirectory.toFile().listFiles((dir, name) -> {
					// Extract index
					Matcher m = gzFileNamePattern.matcher(name);
					return m.matches();
				});
				
				// Sort fileList
				Arrays.sort(fileList);
				
				// Parse content
				StringBuilder fileListString  = new StringBuilder();
				for (File f : fileList) {
					fileListString.append(f.getName()).append("\n");
				}
				Log.debug(LOGGER, "File list: " + fileListString.toString());
				
				if (fileList != null) {
					for (File gzFile : fileList) {
						Log.debug(LOGGER, "Processing file: " + gzFile.getName());
						try {
							Matcher m = gzFileNamePattern.matcher(gzFile.getName());
							if (!m.matches()) {
								Log.debug(LOGGER, "Pattern not matched");
								continue;
							}
							int currentIndex = Integer.parseInt(m.group(1));
							Log.debug(LOGGER, "Current index: " + currentIndex);
							if (currentIndex <= lastProcessedIndex) {
								continue;
							}
							Path file = ChecklistForJira.gunzip(extractDir, gzFile.toPath());
							ChecklistForJiraData data = reader.readValue(file.toFile());
							filesFound.add(FilenameUtils.getName(gzFile.toString()));
							switch(data.getType()) {
							case "manifest": 
								manifestFound = true;
								issueCount = data.getIssueCount();
								Log.debug(LOGGER, "Manifest issue count: " + issueCount);
								lastProcessedIndex = currentIndex;
								break;
							case "values": 
								valueCount += data.getValues().size();
								Log.debug(LOGGER, "Values size: " + data.getValues().size());
								lastProcessedIndex = currentIndex;
								break;
							default: 
								lastProcessedIndex = currentIndex;
								break;
							}
							// If manifest type, get issue count
							// If issue count not 0, look for value type
						} catch (Exception ex) {
							Log.warn(LOGGER, "Error processing " + gzFile, ex);
						}
					}
				}
				
				StringBuilder sb = new StringBuilder();
				sb.append("Custom field: ").append(customField.getFieldId()).append("\n");
				sb.append("Context: ").append(contextId).append("\n");
				sb.append("manifestFound: ").append(manifestFound).append("\n");
				sb.append("issueCount: ").append(issueCount).append("\n");
				sb.append("valueCount: ").append(valueCount).append("\n");
				sb.append("lastProcessedIndex: ").append(lastProcessedIndex).append("\n");
				
				Log.debug(LOGGER, sb.toString());
				
				if (manifestFound && (issueCount == 0 || valueCount >= issueCount)) {
					// Completed
					completed = true;
					Log.debug(LOGGER, "Completion detected");
					break;
				} else {
					wait += WAIT;
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
		return "customfield_" + customField.getFieldId() + "-" + contextId;
	}
	
}
