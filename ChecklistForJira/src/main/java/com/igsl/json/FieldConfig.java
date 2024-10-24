package com.igsl.json;

import java.util.Map;

/*
	"fieldConfig": {
	    "id": 10500,
	    "customFieldId": 10400,
	    "name": "Default Configuration Scheme for Checklist",
	    "customFieldName": "Checklist",
	    "projects": {},
	    "issueTypes": {}
	},
*/
public class FieldConfig {
	private int id;
	private int customFieldId;
	private String name;
	private String customFieldName;
	private Map<String, String> projects;	// Id to project key
	private Map<String, String> issueTypes;	// Id to issue type name
	public int getId() {
		return id;
	}
	public void setId(int id) {
		this.id = id;
	}
	public int getCustomFieldId() {
		return customFieldId;
	}
	public void setCustomFieldId(int customFieldId) {
		this.customFieldId = customFieldId;
	}
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getCustomFieldName() {
		return customFieldName;
	}
	public void setCustomFieldName(String customFieldName) {
		this.customFieldName = customFieldName;
	}
	public Map<String, String> getProjects() {
		return projects;
	}
	public void setProjects(Map<String, String> projects) {
		this.projects = projects;
	}
	public Map<String, String> getIssueTypes() {
		return issueTypes;
	}
	public void setIssueTypes(Map<String, String> issueTypes) {
		this.issueTypes = issueTypes;
	}
}
