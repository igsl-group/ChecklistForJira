package com.igsl;


public class Config {
	public static final String DEFAULT_SCHEME = "https";
	public static final int DEFAULT_CONCURRENT_EXPORT_COUNT = 20;
	public static final long DEFAULT_EXPORT_MAX_WAIT_MS = 3600000;
	
	private String sourceDatabaseURL;
	private String sourceDatabaseUser;
	private String sourceDatabasePassword;

	private String sourceScheme = DEFAULT_SCHEME;
	private String sourceHost;
	private String sourceUser;
	private String sourcePassword;
	
	private String checklistForJiraExportDir;
	private int concurrentExportCount = DEFAULT_CONCURRENT_EXPORT_COUNT;
	private long exportMaxWaitMS = DEFAULT_EXPORT_MAX_WAIT_MS;
	
	private String targetScheme = DEFAULT_SCHEME;
	private String targetHost;
	private String targetUser;
	private String targetToken;
	
	// Generated
	public String getSourceUser() {
		return sourceUser;
	}

	public void setSourceUser(String sourceUser) {
		this.sourceUser = sourceUser;
	}

	public String getSourcePassword() {
		return sourcePassword;
	}

	public void setSourcePassword(String sourcePassword) {
		this.sourcePassword = sourcePassword;
	}

	public String getSourceDatabaseURL() {
		return sourceDatabaseURL;
	}

	public void setSourceDatabaseURL(String sourceDatabaseURL) {
		this.sourceDatabaseURL = sourceDatabaseURL;
	}

	public String getSourceDatabaseUser() {
		return sourceDatabaseUser;
	}

	public void setSourceDatabaseUser(String sourceDatabaseUser) {
		this.sourceDatabaseUser = sourceDatabaseUser;
	}

	public String getSourceDatabasePassword() {
		return sourceDatabasePassword;
	}

	public void setSourceDatabasePassword(String sourceDatabasePassword) {
		this.sourceDatabasePassword = sourceDatabasePassword;
	}

	public String getSourceScheme() {
		return sourceScheme;
	}

	public void setSourceScheme(String sourceScheme) {
		this.sourceScheme = sourceScheme;
	}

	public String getSourceHost() {
		return sourceHost;
	}

	public void setSourceHost(String sourceHost) {
		this.sourceHost = sourceHost;
	}

	public String getChecklistForJiraExportDir() {
		return checklistForJiraExportDir;
	}

	public void setChecklistForJiraExportDir(String checklistForJiraExportDir) {
		this.checklistForJiraExportDir = checklistForJiraExportDir;
	}

	public long getExportMaxWaitMS() {
		return exportMaxWaitMS;
	}

	public void setExportMaxWaitMS(long exportMaxWaitMS) {
		this.exportMaxWaitMS = exportMaxWaitMS;
	}

	public int getConcurrentExportCount() {
		return concurrentExportCount;
	}

	public void setConcurrentExportCount(int concurrentExportCount) {
		this.concurrentExportCount = concurrentExportCount;
	}

	public String getTargetScheme() {
		return targetScheme;
	}

	public void setTargetScheme(String targetScheme) {
		this.targetScheme = targetScheme;
	}

	public String getTargetHost() {
		return targetHost;
	}

	public void setTargetHost(String targetHost) {
		this.targetHost = targetHost;
	}

	public String getTargetUser() {
		return targetUser;
	}

	public void setTargetUser(String targetUser) {
		this.targetUser = targetUser;
	}

	public String getTargetToken() {
		return targetToken;
	}

	public void setTargetToken(String targetToken) {
		this.targetToken = targetToken;
	}

}
