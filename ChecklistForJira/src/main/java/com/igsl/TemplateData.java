package com.igsl;

import java.util.ArrayList;
import java.util.List;

public class TemplateData {
	private String templateName;
	private List<String> issueTypeNameList = new ArrayList<>();	// Issue type name
	public String getTemplateName() {
		return templateName;
	}
	public void setTemplateName(String templateName) {
		this.templateName = templateName;
	}
	public List<String> getIssueTypeNameList() {
		return issueTypeNameList;
	}
	public void setIssueTypeNameList(List<String> issueTypeNameList) {
		this.issueTypeNameList = issueTypeNameList;
	}
}