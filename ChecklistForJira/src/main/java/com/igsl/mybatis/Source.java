package com.igsl.mybatis;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class Source {
	private String source;
	private List<Project> projectList;
	@JsonIgnore
	public Map<String, Project> getProjectMap() {
		return projectList.stream().collect(Collectors.toMap(Project::getProjectId, item -> item));
	}
	public List<Project> getProjectList() {
		return projectList;
	}
	public void setProjectList(List<Project> projectList) {
		this.projectList = projectList;
	}
	public String getSource() {
		return source;
	}
	public void setSource(String source) {
		this.source = source;
	}

}
