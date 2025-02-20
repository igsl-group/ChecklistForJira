package com.igsl;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.igsl.json.ChecklistForJiraData;
import com.igsl.mybatis.CustomField;

public class FileMonitorThread implements Callable<ExportResult> {

	private static ObjectMapper OM = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
	private static final Logger LOGGER = LogManager.getLogger();
	private static final long WAIT = 1000;

	private Path exportDirectory;
	private CustomField customField;
	private String contextId;
	private long maxWait;

	public FileMonitorThread(Path exportDirectory, CustomField customField, String contextId, long maxWait) {
		this.exportDirectory = exportDirectory;
		this.customField = customField;
		this.contextId = contextId;
		this.maxWait = maxWait;
	}
	
	@Override
	public ExportResult call() {
		Instant startTime = Instant.now();
		// TODO Monitor using job? It can disappear quickly
		// Group: ExportChecklistJobRunner
		// Job: ?-[Checklist Context ID]
		
		Log.info(LOGGER, "Verification started for " + getName());
		Pattern gzFileNamePattern = Pattern.compile("customfield_" + customField.getFieldId() + "-" + contextId + "-([0-9])\\.gz");
		ExportResult result = new ExportResult();
		Path extractDir = Paths.get(getName());
		boolean completed = false;
		int lastProcessedIndex = 0;
		boolean manifestFound = false;
		long issueCount = 0;
		long valueCount = 0;
		String errorMessage = null;
		List<String> filesFound = new ArrayList<>();
		try {
			extractDir = Files.createDirectory(extractDir);
			ObjectReader reader = OM.readerFor(ChecklistForJiraData.class);
			long wait = 0;
			while (true) {
				if (wait > maxWait) {
					Log.error(LOGGER, "Timeout waiting for GZ files");
					errorMessage = "Timed out: " + wait;
					break;
				}
				// Find GZ file
				DirectoryStream.Filter<Path> pathFilter = new DirectoryStream.Filter<Path>() {
					@Override
					public boolean accept(Path entry) {
						Matcher m = gzFileNamePattern.matcher(entry.getFileName().toString());
						return m.matches();
					}
				};

				// Sort fileList
				DirectoryStream<Path> fileStream = Files.newDirectoryStream(exportDirectory, pathFilter);
				List<Path> fileList = StreamSupport.stream(fileStream.spliterator(), false)
					.sorted(Comparator.comparing(Path::toString))
					.collect(Collectors.toList());
				for (Path gzFile : fileList) {
					Log.debug(LOGGER, "Processing file: " + gzFile.toString());
					try {
						Matcher m = gzFileNamePattern.matcher(gzFile.getFileName().toString());
						if (!m.matches()) {
							Log.debug(LOGGER, "Pattern not matched");
							continue;
						}
						int currentIndex = Integer.parseInt(m.group(1));
						Log.debug(LOGGER, "Current index: " + currentIndex);
						if (currentIndex <= lastProcessedIndex) {
							continue;
						}
						Path file = ChecklistForJira.gunzip(extractDir, gzFile);
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
						Log.warn(LOGGER, "Error processing " + gzFile + ", will retry", ex);
					}						
				};
				
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
					errorMessage = null;
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
					result.addItem(customField, contextId, null, file);
				});
			} else {
				result.addItem(customField, contextId, errorMessage, null);
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

	public String getName() {
		return "customfield_" + customField.getFieldId() + "-" + contextId;
	}
	
}
