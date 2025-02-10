package com.igsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.igsl.mybatis.CustomField;

public class ExportResult {
	
	/**
	 * Key is customfield_[field id]-[context id]
	 * Value is list of files found
	 */
	private Map<String, List<String>> resultMap = new TreeMap<>();
	
	public void addItem(CustomField cf, String contextId, String item) {
		String name = "Field: " + cf.getFieldName() + " File: customfield_" + cf.getFieldId() + "-" + contextId;
		if (!resultMap.containsKey(name)) {
			resultMap.put(name, new ArrayList<>());
		}
		if (item != null) {
			resultMap.get(name).add(item);
		}
	}
	
	public void addAll(ExportResult er) {
		resultMap.putAll(er.getResultMap());
	}
	
	public Map<String, List<String>> getResultMap() {
		return resultMap;
	}

	public void setResultMap(Map<String, List<String>> resultMap) {
		this.resultMap = resultMap;
	}
	
}
