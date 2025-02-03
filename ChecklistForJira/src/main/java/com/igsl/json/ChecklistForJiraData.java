package com.igsl.json;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Checklist for Jira export format
 */
public class ChecklistForJiraData {
	private List<ChecklistItem> globalItems;
	private FieldConfig fieldConfig;
	private String type;
	private Map<String, String> issueTypes;
	private Map<String, String> projects;
	@JsonIgnore
	public boolean isManifest() {
		return "manifest".equals(type);
	}
	public FieldConfig getFieldConfig() {
		return fieldConfig;
	}
	public void setFieldConfig(FieldConfig fieldConfig) {
		this.fieldConfig = fieldConfig;
	}
	public String getType() {
		return type;
	}
	public void setType(String type) {
		this.type = type;
	}
	public List<ChecklistItem> getGlobalItems() {
		return globalItems;
	}
	public void setGlobalItems(List<ChecklistItem> globalItems) {
		this.globalItems = globalItems;
	}
	public Map<String, String> getIssueTypes() {
		return issueTypes;
	}
	public void setIssueTypes(Map<String, String> issueTypes) {
		this.issueTypes = issueTypes;
	}
	public Map<String, String> getProjects() {
		return projects;
	}
	public void setProjects(Map<String, String> projects) {
		this.projects = projects;
	}
}
