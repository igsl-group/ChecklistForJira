package com.igsl.mybatis;

import java.util.Comparator;

public class Project implements Comparable<Project> {
	private static final Comparator<String> STRING_COMPARATOR = Comparator.nullsFirst(Comparator.naturalOrder());
	private String projectId;
	private String projectKey;
	private String projectName;
	@Override
	public int compareTo(Project o) {
		if (o != null) {
			return STRING_COMPARATOR.compare(getProjectKey(), o.getProjectKey());
		}
		return 0;
	}
	public String getProjectId() {
		return projectId;
	}
	public void setProjectId(String projectId) {
		this.projectId = projectId;
	}
	public String getProjectKey() {
		return projectKey;
	}
	public void setProjectKey(String projectKey) {
		this.projectKey = projectKey;
	}
	public String getProjectName() {
		return projectName;
	}
	public void setProjectName(String projectName) {
		this.projectName = projectName;
	}
}
