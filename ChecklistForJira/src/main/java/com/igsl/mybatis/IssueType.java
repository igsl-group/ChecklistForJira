package com.igsl.mybatis;

public class IssueType {
	public static final String ALL_ISSUE_TYPES_ID = "0";
	public static final String UNASSIGNED_ISSUE_TYPE_NAME = "Unassigned";
	public static final String ALL_ISSUE_TYPE_NAME = "All";
	private String issueTypeId;
	private String issueTypeName;
	public String getIssueTypeId() {
		return issueTypeId;
	}
	public void setIssueTypeId(String issueTypeId) {
		this.issueTypeId = issueTypeId;
	}
	public String getIssueTypeName() {
		return issueTypeName;
	}
	public void setIssueTypeName(String issueTypeName) {
		this.issueTypeName = issueTypeName;
	}
}
