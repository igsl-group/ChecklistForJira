package com.igsl.postfunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.igsl.Log;
import com.igsl.mybatis.CustomField;
import com.igsl.mybatis.Project;

public class ChecklistFunction {

	private static final Logger LOGGER = LogManager.getLogger();
	private static final XPathFactory XPATH_FACTORY = XPathFactory.newInstance();
	private static final Pattern SELECTED_CUSTOM_FIELD_IDS_PATTERN = Pattern.compile("customfield_([0-9]+)");
	
	// Usage data
	private List<Project> associatedProjects;
	
	// Workflow data
	private String workflowName;
	private String transitionId;
	private String transitionName;
	
	// Post-function data
	private String setStatusToUnchecked;
	private String removeStatusRegex;
	private String checkRegex;
	private String setStatusRegexPattern;
	private String functionName;
	private int checklistItemCount;
	private String statusAction;
	private boolean avoidDuplicates;
	private String uncheckRegex;
	private String selectedCustomfieldIds;
	private List<String> selectedCustomFieldIdList;
	private String setStatusAll;
	private String rulesData;
	private boolean modificationFromTemplate;
	private boolean generateHistoryLog;
	private String fullModuleKey;
	private String itemCompletionAction;
	private List<String> items;
	private String modificationTemplateId;
	private boolean appendOperation;
	private String className;
	private String setStatusRegexValue;
	private boolean initialTransition;
	
	private static int getIntArgument(Node n, String argumentName, int defaultValue) {
		String s = getArgument(n, argumentName);
		if (s != null) {
			try {
				return Integer.parseInt(s);
			} catch (NumberFormatException nfex) {
				return defaultValue;
			}
		}
		return defaultValue;
	}

	private static boolean getBoolArgument(Node n, String argumentName, boolean defaultValue) {
		String s = getArgument(n, argumentName);
		if (s != null) {
			return Boolean.parseBoolean(s);
		}
		return defaultValue;
	}
	
	private static String getArgument(Node n, String argumentName) {
		XPath xpath = XPATH_FACTORY.newXPath();
		try {
			Node arg = (Node) xpath.evaluate("./arg[@name='" + argumentName + "']", n, XPathConstants.NODE);
			return arg.getTextContent();
		} catch (Exception ex) {
			return null;
		}
	}
	
	private static String getAttribute(Node n, String attributeName) {
		NamedNodeMap attributes = n.getAttributes();
		Node attribute = attributes.getNamedItem(attributeName);
		if (attribute != null) {
			return attribute.getNodeValue();
		}
		return null;
	}
	
	@JsonIgnore
	public String getSelectedCustomFieldIdNames(Map<String, CustomField> fieldMap) {
		StringBuilder result = new StringBuilder();
		for (String id : selectedCustomFieldIdList) {
			if (fieldMap.containsKey(id)) {
				result.append(",").append(fieldMap.get(id).getFieldName());
			} else {
				result.append(",").append(id);
			}
		}
		if (result.length() != 0) {
			result.delete(0, 1);
		}
		return result.toString();
	}
	
	/**
	 * Initialize from XML Node
	 * @param postFunction
	 */
	public ChecklistFunction(String workflowName, Node postFunction, Node transition) {
		this.setStatusToUnchecked = getArgument(postFunction, "setStatusToUnchecked");
		this.removeStatusRegex = getArgument(postFunction, "removeStatusRegex");
		this.checkRegex = getArgument(postFunction, "checkRegex");
		this.setStatusRegexPattern = getArgument(postFunction, "setStatusRegexPattern");
		this.functionName = getArgument(postFunction, "functionName");
		this.checklistItemCount = getIntArgument(postFunction, "checklistItemCount", 0);
		this.statusAction = getArgument(postFunction, "statusAction");
		this.avoidDuplicates = getBoolArgument(postFunction, "avoidDuplicates", false);
		this.uncheckRegex = getArgument(postFunction, "uncheckRegex");
		this.selectedCustomfieldIds = getArgument(postFunction, "selectedCustomfieldIds");
		this.setStatusAll = getArgument(postFunction, "setStatusAll");
		this.rulesData = getArgument(postFunction, "rulesData");
		this.modificationFromTemplate = getBoolArgument(postFunction, "modificationFromTemplate", false);
		this.generateHistoryLog = getBoolArgument(postFunction, "generateHistoryLog", false);
		this.fullModuleKey = getArgument(postFunction, "full.module.key");
		this.itemCompletionAction = getArgument(postFunction, "itemCompletionAction");
		this.modificationTemplateId = getArgument(postFunction, "modificationTemplateId");
		this.appendOperation = getBoolArgument(postFunction, "appendOperation", false);
		this.className = getArgument(postFunction, "class.name");
		this.setStatusRegexValue = getArgument(postFunction, "setStatusRegexValue");
		this.items = new ArrayList<>();
		for (int i = 0; i < this.checklistItemCount; i++) {
			String itemString = getArgument(postFunction, "item_" + i);
			if (itemString != null) {
				this.items.add(itemString);
			} else {
				this.items.add("");
			}
		}
		// Parse selectedCustomFieldIds apart
		selectedCustomFieldIdList = new ArrayList<>();
		Matcher matcher = SELECTED_CUSTOM_FIELD_IDS_PATTERN.matcher(this.selectedCustomfieldIds);
		while (matcher.find()) {
			selectedCustomFieldIdList.add(matcher.group(1));
		}
		
		this.initialTransition = false;
		try {
			XPath xpath = XPATH_FACTORY.newXPath();
			Node initialAction = (Node) xpath.evaluate("./ancestor::initial-actions", transition, XPathConstants.NODE);
			this.initialTransition = (initialAction != null);
		} catch (XPathExpressionException e) {
			Log.error(LOGGER, "Error checking for initial-actions ancestor", e);
		}
		
		this.transitionName = getAttribute(transition, "name");
		this.transitionId = getAttribute(transition, "id");
		
		this.workflowName = workflowName;
	}
	
	public String getSetStatusToUnchecked() {
		return setStatusToUnchecked;
	}
	public void setSetStatusToUnchecked(String setStatusToUnchecked) {
		this.setStatusToUnchecked = setStatusToUnchecked;
	}
	public String getRemoveStatusRegex() {
		return removeStatusRegex;
	}
	public void setRemoveStatusRegex(String removeStatusRegex) {
		this.removeStatusRegex = removeStatusRegex;
	}
	public String getCheckRegex() {
		return checkRegex;
	}
	public void setCheckRegex(String checkRegex) {
		this.checkRegex = checkRegex;
	}
	public String getSetStatusRegexPattern() {
		return setStatusRegexPattern;
	}
	public void setSetStatusRegexPattern(String setStatusRegexPattern) {
		this.setStatusRegexPattern = setStatusRegexPattern;
	}
	public String getFunctionName() {
		return functionName;
	}
	public void setFunctionName(String functionName) {
		this.functionName = functionName;
	}
	public int getChecklistItemCount() {
		return checklistItemCount;
	}
	public void setChecklistItemCount(int checklistItemCount) {
		this.checklistItemCount = checklistItemCount;
	}
	public String getStatusAction() {
		return statusAction;
	}
	public void setStatusAction(String statusAction) {
		this.statusAction = statusAction;
	}
	public boolean isAvoidDuplicates() {
		return avoidDuplicates;
	}
	public void setAvoidDuplicates(boolean avoidDuplicates) {
		this.avoidDuplicates = avoidDuplicates;
	}
	public String getUncheckRegex() {
		return uncheckRegex;
	}
	public void setUncheckRegex(String uncheckRegex) {
		this.uncheckRegex = uncheckRegex;
	}
	public String getSelectedCustomfieldIds() {
		return selectedCustomfieldIds;
	}
	public void setSelectedCustomfieldIds(String selectedCustomfieldIds) {
		this.selectedCustomfieldIds = selectedCustomfieldIds;
	}
	public String getSetStatusAll() {
		return setStatusAll;
	}
	public void setSetStatusAll(String setStatusAll) {
		this.setStatusAll = setStatusAll;
	}
	public String getRulesData() {
		return rulesData;
	}
	public void setRulesData(String rulesData) {
		this.rulesData = rulesData;
	}
	public boolean isModificationFromTemplate() {
		return modificationFromTemplate;
	}
	public void setModificationFromTemplate(boolean modificationFromTemplate) {
		this.modificationFromTemplate = modificationFromTemplate;
	}
	public boolean isGenerateHistoryLog() {
		return generateHistoryLog;
	}
	public void setGenerateHistoryLog(boolean generateHistoryLog) {
		this.generateHistoryLog = generateHistoryLog;
	}
	public String getFullModuleKey() {
		return fullModuleKey;
	}
	public void setFullModuleKey(String fullModuleKey) {
		this.fullModuleKey = fullModuleKey;
	}
	public String getItemCompletionAction() {
		return itemCompletionAction;
	}
	public void setItemCompletionAction(String itemCompletionAction) {
		this.itemCompletionAction = itemCompletionAction;
	}
	public List<String> getItems() {
		return items;
	}
	public void setItems(List<String> items) {
		this.items = items;
	}
	public String getModificationTemplateId() {
		return modificationTemplateId;
	}
	public void setModificationTemplateId(String modificationTemplateId) {
		this.modificationTemplateId = modificationTemplateId;
	}
	public boolean isAppendOperation() {
		return appendOperation;
	}
	public void setAppendOperation(boolean appendOperation) {
		this.appendOperation = appendOperation;
	}
	public String getClassName() {
		return className;
	}
	public void setClassName(String className) {
		this.className = className;
	}
	public String getSetStatusRegexValue() {
		return setStatusRegexValue;
	}
	public void setSetStatusRegexValue(String setStatusRegexValue) {
		this.setStatusRegexValue = setStatusRegexValue;
	}

	public List<Project> getAssociatedProjects() {
		return associatedProjects;
	}

	public void setAssociatedProjects(List<Project> associatedProjects) {
		this.associatedProjects = associatedProjects;
	}

	public String getWorkflowName() {
		return workflowName;
	}

	public void setWorkflowName(String workflowName) {
		this.workflowName = workflowName;
	}

	public String getTransitionId() {
		return transitionId;
	}

	public void setTransitionId(String transitionId) {
		this.transitionId = transitionId;
	}

	public String getTransitionName() {
		return transitionName;
	}

	public void setTransitionName(String transitionName) {
		this.transitionName = transitionName;
	}

	public List<String> getSelectedCustomFieldIdList() {
		return selectedCustomFieldIdList;
	}

	public void setSelectedCustomFieldIdList(List<String> selectedCustomFieldIdList) {
		this.selectedCustomFieldIdList = selectedCustomFieldIdList;
	}

	public boolean isInitialTransition() {
		return initialTransition;
	}

	public void setInitialTransition(boolean initialTransition) {
		this.initialTransition = initialTransition;
	}
}
