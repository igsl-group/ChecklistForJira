<#
    .SYNOPSIS 
    Export Checklist for Jira settings from Jira Server/Data Center.
    
    .PARAMETER FormatTemplates
        Switch. Output templates from exported files into CSV format suitable for playbook. 
    
    .PARAMETER ExportUsage
        Switch. Export Checklist for Jira custom field usage in screens, projects and workflows. 
        With FormatTemplates this is obsolete, but still useful if you want to check the raw data.
        Requires CSV files from database queries. 
    
    .PARAMETER CompareSettings
        Switch. Extract settings from Checklist for Jira export files and produce a CSV. 
        You can then compare the different settings used. 
    
    .PARAMETER ExportFieldUseData
        Switch. Export Checklist for Jira custom field usage in screens. 
        This is obsolete, use -Export instead.
        
    .PARAMETER ExportChecklist
        Switch. Trigger export for all Checklist for Jira custom fields and application contexts. 
        Get the export files from /Application Data/export/checklist.
    
    .PARAMETER ServerProtocol
        Protocol for Jira Server/Data Center, http/https. Defaults to https.
    
    .PARAMETER ServerDomain
        Jira Server/Data Center domain, e.g. jira-plike.pccwglobal.com
        
    .PARAMETER ServerUser
        User account, e.g. x-igsl-kw
        
    .PARAMETER ServerPassword
        Account password.
        
    .PARAMETER ComparePath
        Directory containing 
#>
Param(
    [Parameter(Mandatory, ParameterSetName = 'FormatTemplates')]
    [switch] $FormatTemplates,

    [Parameter(Mandatory, ParameterSetName = 'CompareSettings')]
    [switch] $CompareSettings,
    
    [Parameter(Mandatory, ParameterSetName = 'ExportChecklist')]
    [switch] $ExportChecklist,

    [Parameter(Mandatory, ParameterSetName = 'ExportFieldUseData')]
    [switch] $ExportFieldUseData,
    
    [Parameter(Mandatory, ParameterSetName = 'ExportUsage')]
    [switch] $ExportUsage,
    
    [Parameter(Mandatory, ParameterSetName = 'CompareSettings')]
    [string] $ComparePath,

    [Parameter(ParameterSetName = 'ExportUsage')]
    [Parameter(ParameterSetName = 'ExportChecklist')]
    [Parameter(ParameterSetName = 'ExportFieldUseData')]
    [Parameter(ParameterSetName = 'FormatTemplates')]
    [string] $ServerProtocol = "https",
    
    [Parameter(Mandatory, ParameterSetName = 'ExportUsage')]
    [Parameter(Mandatory, ParameterSetName = 'ExportChecklist')]
    [Parameter(Mandatory, ParameterSetName = 'ExportFieldUseData')]
    [Parameter(Mandatory, ParameterSetName = 'FormatTemplates')]
    [string] $ServerDomain,
    
    [Parameter(Mandatory, ParameterSetName = 'ExportUsage')]
    [Parameter(Mandatory, ParameterSetName = 'ExportChecklist')]
    [Parameter(Mandatory, ParameterSetName = 'ExportFieldUseData')]
    [Parameter(Mandatory, ParameterSetName = 'FormatTemplates')]
    [string] $ServerUser,
    
    [Parameter(ParameterSetName = 'ExportUsage')]
    [Parameter(ParameterSetName = 'ExportChecklist')]
    [Parameter(ParameterSetName = 'ExportFieldUseData')]
    [Parameter(ParameterSetName = 'FormatTemplates')]
    [string] $ServerPassword = '',
    
    [Parameter(Mandatory, ParameterSetName = 'FormatTemplates')]
    [string] $ChecklistExportDir,
    
    # Created by ExportFieldUseData mode
    [Parameter(Mandatory, ParameterSetName = 'ExportUsage')]
    [Parameter(Mandatory, ParameterSetName = 'FormatTemplates')]
    [string] $FieldToScreenFile,
    
    <#
        From SQL: 
        SELECT 
            itss.NAME AS IssueTypeScreenScheme,
            it.pname AS IssueTypeName,
            p.pname AS ProjectName,
            p.pkey AS ProjectKey,
            p.id AS ProjectID,
            fss.NAME AS ScreenScheme,
            fs.NAME AS ScreenName,
            fs.ID AS ScreenID
        FROM
            issuetypescreenscheme itss
            LEFT JOIN issuetypescreenschemeentity itsse ON itsse.SCHEME = itss.ID
            LEFT JOIN issuetype it ON it.ID = itsse.ISSUETYPE
            LEFT JOIN nodeassociation na ON na.SINK_NODE_ID = itss.ID AND na.SINK_NODE_ENTITY = 'IssueTypeScreenScheme'
            LEFT JOIN project p ON p.ID = na.SOURCE_NODE_ID AND na.SINK_NODE_ENTITY = 'IssueTypeScreenScheme'
            LEFT JOIN fieldscreenscheme fss ON fss.ID = itsse.FIELDSCREENSCHEME
            LEFT JOIN fieldscreenschemeitem fssi ON fssi.FIELDSCREENSCHEME = fss.ID
            LEFT JOIN fieldscreen fs ON fs.ID = fssi.FIELDSCREEN
        GROUP BY 
            IssueTypeScreenScheme,
            IssueTypeName,
            ProjectName,
            ProjectKey,
            ProjectID,
            ScreenScheme,
            ScreenName,
            ScreenID
        ORDER BY ScreenID, ProjectKey
        INTO OUTFILE 'Screen.csv'
        FIELDS TERMINATED BY ','
        ENCLOSED BY '"'
        LINES TERMINATED BY '\n';
    #>
    [Parameter(ParameterSetName = 'ExportUsage')]
    [Parameter(ParameterSetName = 'FormatTemplates')]
    [string] $ProjectToScreenFile = "Screen.csv",
    
    <#
        From SQL:
        SELECT
            p.pname AS ProjectName,
            p.pkey AS ProjectKey,
            p.id AS ProjectID,
            it.pname AS IssueType,
            wf.workflowname AS WorkflowName
        FROM 
            project p 
            LEFT JOIN nodeassociation na ON na.SOURCE_NODE_ID = p.ID AND na.SINK_NODE_ENTITY = 'WorkflowScheme'
            LEFT JOIN workflowscheme wfs ON wfs.ID = na.SINK_NODE_ID AND na.SINK_NODE_ENTITY = 'WorkflowScheme'
            LEFT JOIN workflowschemeentity wfse ON wfse.SCHEME = wfs.ID 
            LEFT JOIN issuetype it ON it.ID = wfse.issuetype
            LEFT JOIN jiraworkflows wf ON wf.workflowname = wfse.WORKFLOW
        INTO OUTFILE 'Workflow.csv'
        FIELDS TERMINATED BY ','
        ENCLOSED BY '"'
        LINES TERMINATED BY '\n';
    #>
    [Parameter(ParameterSetName = 'ExportUsage')]
    [Parameter(ParameterSetName = 'FormatTemplates')]
    [string] $ProjectToWorkflowFile = "Workflow.csv",
    
    <#
        From SQL:
        SELECT * FROM jiraworkflows 
        INTO OUTFILE 'WorkflowDetails.csv'
        FIELDS TERMINATED BY ','
        ENCLOSED BY '"'
        LINES TERMINATED BY '\n';
    #>
    [Parameter(ParameterSetName = 'ExportUsage')]
    [Parameter(ParameterSetName = 'FormatTemplates')]
    [string] $WorkflowDetailsFile = "WorkflowDetails.csv"
)

class RestException : Exception {
    RestException($Message) : base($Message) {
    }
}

class TemplateData {
    [string] $CustomFieldId
    [string] $CustomFieldName
    [string] $TemplateName
    [string] $TemplateText
    [hashtable] $Projects # Key: Project Name, Value: List of Issue Type Names
    TemplateData($CustomFieldId, $CustomFieldName, $TemplateName) {
        $this.CustomFieldId = $CustomFieldId
        $this.CustomFieldName = $CustomFieldName
        $this.TemplateName = $TemplateName
        $this.TemplateText = ''
        $this.Projects = @{}
    }
}

function GetAuthHeader {
    Param (
        [string] $ServerUser,
        [string] $ServerPassword
    )
    [hashtable] $Headers = @{
        "Content-Type" = "application/json"
    }
    $Auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($ServerUser + ":" + $ServerPassword))
    $Headers.Authorization = "Basic " + $Auth
    $Headers
}

# Call Invoke-WebRequest without throwing exception on 4xx/5xx 
function WebRequest {
    Param (
        [string] $Uri,
        [string] $Method,
        [hashtable] $Headers,
        [object] $Body
    )
    $Response = $null
    try {
        $script:ProgressPreference = 'SilentlyContinue'    # Subsequent calls do not display UI.
        $Response = Invoke-WebRequest -Method $Method -Header $Headers -Uri $Uri -Body $Body
    } catch {
        $Response = @{}
        $Response.StatusCode = $_.Exception.Response.StatusCode.value__
        $Response.content = $_.Exception.Message
    } finally {
        $script:ProgressPreference = 'Continue'            # Subsequent calls do display UI.
    }
    $Response
}

# Return map of FieldID = FieldName and FieldName = FieldID as array
function GetChecklistCustomField_IDNameMaps {
        Param(
        [hashtable] $Headers
    )
    $Uri = $ServerProtocol + '://' + $ServerDomain + '/rest/api/2/customFields'
    $Body = @{
        "startAt" = 1;
        "maxResults" = 100;
    }
    # Note: Paging not handled
    $NameToID = @{}
    $IDToName = @{}
    try {
        $StartAt = 1
        $IsLast = $False
        do {
            $Body["startAt"] = $StartAt
            $Response = WebRequest $Uri "GET" $Headers $Body
            if ($Response.StatusCode -ne 200) {
                throw $Response.Content
            }
            $Json = $Response.Content | ConvertFrom-Json
            foreach ($Item in $Json.values) {
                if ($Item.type -in @('Checklist', 'Checklist Read-Only Proxy', 'Checklist Proxy for Customer Portal')) {
                    $ItemName = $Item.name
                    $ItemID = $Item.id
                    $IDToName["${ItemID}"] = $ItemName
                    $NameToID["${ItemName}"] = $ItemID
                }
            }
            $StartAt += 1
            $IsLast = $Json.isLast
        } while (-not $IsLast)
    } catch [RestException] {
        throw $PSItem
    }
    @($IDToName, $NameToID)
}

function GetChecklistCustomFields {
    Param(
        [hashtable] $Headers
    )
    $Uri = $ServerProtocol + '://' + $ServerDomain + '/rest/api/2/customFields'
    $Body = @{
        "search" = "Checklist";
        "startAt" = 0;
        "maxResults" = 100;
    }
    # Note: Paging not handled
    $Result = [System.Collections.ArrayList]::new()
    try {
        $Response = WebRequest $Uri "GET" $Headers $Body
        if ($Response.StatusCode -ne 200) {
            throw $Response.Content
        }
        $Json = $Response.Content | ConvertFrom-Json
        foreach ($Item in $Json.values) {
            if ($Item.type -in @('Checklist', 'Checklist Read-Only Proxy', 'Checklist Proxy for Customer Portal')) {
                [void] $Result.Add($Item)
            }
        }
    } catch [RestException] {
        throw $PSItem
    }
    $Result
}

function GetProjects {
    Param(
        [hashtable] $Headers
    )
    $Uri = $ServerProtocol + '://' + $ServerDomain + '/rest/api/2/project'
    $Result = @{}
    try {
        $Response = WebRequest $Uri "GET" $Headers
        if ($Response.StatusCode -ne 200) {
            throw $Response.Content
        }
        $Json = $Response.Content | ConvertFrom-Json
        foreach ($Item in $Json) {
            $Result[$Item.id] = @($Item.name, $Item.key)
        }
    } catch [RestException] {
        throw $PSItem
    }
    $Result
}

function GetIssueTypes {
    Param(
        [hashtable] $Headers
    )
    $Uri = $ServerProtocol + '://' + $ServerDomain + '/rest/api/2/issuetype'
    $Result = @{}
    try {
        $Response = WebRequest $Uri "GET" $Headers
        if ($Response.StatusCode -ne 200) {
            throw $Response.Content
        }
        $Json = $Response.Content | ConvertFrom-Json
        foreach ($Item in $Json) {
            $Result[$Item.id] = $Item.name
        }
    } catch [RestException] {
        throw $PSItem
    }
    $Result
}

function GetChecklistFieldsInScreens {
    Param(
        [hashtable] $Headers
    )
    # Map of checklist field ids to map of screen id to name
    $Uri = $ServerProtocol + '://' + $ServerDomain
    $Result = @{}
    try {
        # Get all screens
        $ScreenResp = WebRequest "${Uri}/rest/api/2/screens" "GET" $Headers
        if ($ScreenResp.StatusCode -ne 200) {
            throw $ScreenResp.Content
        }
        $ScreenList = $ScreenResp.Content | ConvertFrom-Json
        Write-Host "Screen count: " $ScreenList.Length
        foreach ($Screen in $ScreenList) {
            $ScreenId = $Screen.id
            $ScreenName = $Screen.name
            # For each screen, get all tabs
            $TabResp = WebRequest "${Uri}/rest/api/2/screens/${ScreenId}/tabs" "GET" $Headers
            if ($TabResp.StatusCode -ne 200) {
                throw $TabResp.Content
            }
            $TabList = $TabResp.Content | ConvertFrom-Json
            Write-Host "Screen: " $ScreenName " (" $ScreenId ") Tab count: " $TabList.Length
            foreach ($Tab in $TabList) {
                # For each tab, get all fields
                $TabId = $Tab.id
                $TabName = $Tab.name
                $FieldResp = WebRequest "${Uri}/rest/api/2/screens/${ScreenId}/tabs/${TabId}/fields" "GET" $Headers
                if ($FieldResp.StatusCode -ne 200) {
                    throw $FieldResp.Content
                }
                $FieldList = $FieldResp.Content | ConvertFrom-Json
                Write-Host "Screen: " $ScreenName " (" $ScreenId ") Tab: " $TabName " Field count: " $FieldList.Length
                foreach ($Field in $FieldList) {
                    if ($Field.type -eq "Checklist") {
                        $FieldId = $Field.id
                        $FieldName = $Field.name
                        if ($Result["${FieldId}"]) {
                            $Result["${FieldId}"]["${ScreenId}"] = "${ScreenName}"
                        } else {
                            $Result["${FieldId}"] = @{}
                            $Result["${FieldId}"]["${ScreenId}"] = "${ScreenName}"
                        }
                        Write-Host "${FieldName} (${FieldId}) found in ${ScreenName} (${ScreenId})"
                    }
                }
            }
        }
    } catch [RestException] {
        throw $PSItem
    }
    $Result
}

function ServerLogin {
    try {
        try {
            $script:ProgressPreference = 'SilentlyContinue'    # Subsequent calls do not display UI.
            # Login
            $LoginBody = @{
                "os_username"=$ServerUser; 
                "os_password"=$ServerPassword
            }
            $Response = Invoke-WebRequest -Method 'POST' -Uri "${ServerProtocol}://${ServerDomain}/login.jsp" -Body $LoginBody -SessionVariable Session
            $Atl = $($Resp | Select -ExpandProperty inputfields | Where-Object -Property 'name' -Value 'atl_token' -EQ)
            if ($Atl.value) {
                $atl_token = $Atl.value
            } else {
                $atl_token = $null
            }
            # Get admin access
            $AdminBody = @{
                "webSudoPassword" = $ServerPassword;
                "webSudoDestination" = "/secure/admin/ViewIssueTypes.jspa"
                "webSudoIsPost" = $false;
                "atl_token" = $atl_token
            }
            $Response = Invoke-WebRequest -Method 'POST' -Uri "${ServerProtocol}://${ServerDomain}/secure/admin/WebSudoAuthenticate.jspa" -Body $AdminBody -WebSession $Session
        } catch {
            $Response = @{}
            $Response.StatusCode = $_.Exception.Response.StatusCode.value__
            $Response.content = $_.Exception.Message
        } finally {
            $script:ProgressPreference = 'Continue'            # Subsequent calls do display UI.
        }
        if ($Response.StatusCode -ne 200) {
            throw $Response.Content
        }
        $Session
    } catch [RestException] {
        throw $PSItem
    }
}

# Decompress InputPath to OutputPath.
function GUnzip {
    param (
        [string] $InputPath,
        [string] $OutputPath
    )
    $InStream = $null
    $OutStream = $null
    try {
        $InStream = [System.IO.File]::Open($InputPath, [System.IO.FileMode]::Open)
        $OutStream = [System.IO.File]::Create($OutputPath)
        $Gzip = [System.IO.Compression.GZipStream]::new($InStream, [System.IO.Compression.CompressionMode]::Decompress)
        $Gzip.CopyTo($OutStream)
    } finally {
        if ($InStream) {
            $InStream.Close()
        }
        if ($OutStream) {
            $OutStream.Close()
        }
    }
}

function WriteCSV {
    param (
        [Parameter(Mandatory, Position = 0)][string] $Csv,
        [Parameter(Mandatory, Position = 1)][string[]] $Items,
        [Parameter(Position = 2)][boolean] $Overwrite = $False
    )
    if ($Overwrite) {
        Set-Content -Path $Csv -Value "" -NoNewLine
    }
    $Line = ""
    foreach ($Item in $Items) {
        # Escape double quotes
        $Item = $Item -Replace '"', '""'
        $Line += ",`"${Item}`""
    }
    $Line = $Line.Substring(1)
    Add-Content -Path $Csv -Value $Line
}

function LoadScreenToWorkflow {
    param (
        [string] $WorkflowFile,
        [string] $WorkflowDetailsFile
    )
    $Result = @{}
    $ProjectToWorkflow = Import-Csv -Header @("ProjectName","ProjectKey","ProjectId","IssueType","WorkflowName") -Path $WorkflowFile
    $ProjectWorkflowMap = @{}
    foreach ($Project in $ProjectToWorkflow) {
        $WorkflowName = $Project.WorkflowName
        if (-not $ProjectWorkflowMap["${WorkflowName}"]) {
            $ProjectWorkflowMap["${WorkflowName}"] = @{}
        }
        if ($Project.IssueType -eq '\N') {
            $Project.IssueType = 'All'
        }
        $ProjectWorkflowMap["${WorkflowName}"] = "(" + $Project.ProjectName + ":" + $Project.ProjectKey + ":" + $Project.ProjectId + ") = " + $Project.IssueType 
    } 
    # Fix \ line continuation
    $WorkflowDetailsFileContent = Get-Content -Raw $WorkflowDetailsFile
    $WorkflowDetailsFileContent = $WorkflowDetailsFileContent.Replace("\`n", '')
    $WorkflowDetailsFileContent = $WorkflowDetailsFileContent.Replace('\"', '""')
    $WorkflowDetails = $WorkflowDetailsFileContent | ConvertFrom-Csv -Header @("ID","WorkflowName","creatorname","Descriptor","islocked")   
    # $WorkflowDetails = Import-Csv -Header @("ID","WorkflowName","creatorname","Descriptor","islocked") -Path $WorkflowDetailsFile
    foreach ($Workflow in $WorkflowDetails) {
        # Parse $Detail.Descriptor for pattern
        $WorkflowName = $Workflow.WorkflowName
        $Src = $Workflow.Descriptor
        $Xml = [xml] $Src
        $Nodes = $Xml.SelectNodes('//meta[@name = "jira.fieldscreen.id"]/text()')
        foreach ($Node in $Nodes) {
            $Screen = $Node.InnerText
            if (-not $Result["${Screen}"]) {
                $Result["${Screen}"] = [System.Collections.ArrayList]::new()
            }
            # Find project associated to workflow
            if ($ProjectWorkflowMap["${WorkflowName}"]) {
                [void] $Result["${Screen}"].Add("${WorkflowName}" + " = " + $ProjectWorkflowMap["${WorkflowName}"])
            }
        }
    }
    $Result
}

function LoadProjectToScreen {
    param (
        [string] $Csv
    )
    $Result = @{}
    $Data = Import-Csv -Header @("IssueTypeScreenScheme","IssueTypeName","ProjectName","ProjectKey","ProjectId","ScreenScheme","ScreenName","ScreenID") -Path $Csv
    foreach ($Line in $Data) {
        $ScreenID = $Line.ScreenID
        if (-not $Result["${ScreenID}"]) {
            $Result["${ScreenID}"] = [System.Collections.ArrayList]::new()
        }
        if ($Line.IssueTypeName -eq '\N') {
            $Line.IssueTypeName = 'All'
        }
        [void] $Result["${ScreenID}"].Add("(" + $Line.ProjectName + ":" + $Line.ProjectKey + ":" + $Line.ProjectId + ") = " + $Line.IssueTypeName)
    }
    $Result
}

function LoadFieldToScreen {
    param (
        [string] $Csv
    )
    $Result = @{}
    $Data = Import-Csv -Header @('FieldId', 'ScreenId', 'ScreenName') -Path $Csv
    foreach ($Line in $Data) {
        $FieldId = $Line.FieldId
        $ScreenId = $Line.ScreenId
        $ScreenName = $Line.ScreenName
        if (-not $Result["${FieldId}"]) {
            $Result["${FieldId}"] = @{}
        }
        $Result["${FieldId}"]["${ScreenId}"] = $ScreenName
    }
    $Result
}

function ConvertChecklistTemplate {
    param (
        $Items,
        $NewFormat = $False
    )
    $Result = ''
    foreach ($Item in $Items) {
        $Line = ''
        if ($NewFormat) {
            if (-not $Item.disabled) {
                if ($Item.isHeader) {
                    $Line += '--- ' + $Item.name
                } else {
                    if ($Item.mandatory) {
                        $Line += '* '
                    }
                    if ($TemplateItem.status_name) {
                        $Line += '[' + $Item.status_name + '] '
                    } else {
                        # Use a default status
                        $Line += '[open] '
                    }
                    # Parse $Item.name, find if it contains \n>>\n[Text], convert those to description
                    # Server version wants >> and [Text] on two lines
                    # Cloud version wants >> [Text] in one line
                    $MatchInfo = $Item.name | Out-String | Select-String -Pattern '(.+)\n>>\n(.+)'
                    if ($MatchInfo) {
                        # Name and description
                        $Line += $MatchInfo.matches.groups[1].Value + "`n>> " + $MatchInfo.matches.groups[2].Value
                    } else {
                        # Name only
                        $Line += $Item.name
                    }
                }
                $Result += "`n" + $Line
            }
        } else {        
            if ($Item.enabled) {
                if ($Item.isHeader) {
                    $Line += '--- ' + $Item.name
                } else {
                    if ($Item.mandatory) {
                        $Line += '* '
                    }
                    if ($TemplateItem.status_name) {
                        $Line += '[' + $Item.status_name + '] '
                    } else {
                        # Use a default status
                        $Line += '[open] '
                    }
                    # Parse $Item.name, find if it contains \n>>\n[Text], convert those to description
                    # Server version wants >> and [Text] on two lines
                    # Cloud version wants >> [Text] in one line
                    $MatchInfo = $Item.name | Out-String | Select-String -Pattern '(.+)\n>>\n(.+)'
                    if ($MatchInfo) {
                        # Name and description
                        $Line += $MatchInfo.matches.groups[1].Value + "`n>> " + $MatchInfo.matches.groups[2].Value
                    } else {
                        # Name only
                        $Line += $Item.name
                    }
                }
                $Result += "`n" + $Line
            }
        }
    }
    if ($Result) {
        $Result = $Result.Substring(1)
    } else {
        $Result = ' '
    }
    $Result
}

function CreateTempFolder {
    $TS = Get-Date -Format yyyyMMddHHmmss
    $Location = Get-Location
    $Location.Path + '\Temp_' + $TS
}

# Main body
if ($ExportUsage -or $ExportFieldUseData -or $ExportChecklist -or $FormatTemplates) {
    if (-not $ServerPassword) {
        $pwd = Read-Host "Enter password" -AsSecureString
        $ServerPassword = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($pwd))
    }
    $AuthHeader = GetAuthHeader $ServerUser $ServerPassword
}
$ExportDate = Get-Date -Format yyyyMMddHHmmss

if ($ExportUsage) {
    $Csv = "ChecklistForJira.${ExportDate}.csv"
    try {
        $ProjectMap = GetProjects $AuthHeader
        $IssueTypeMap = GetIssueTypes $AuthHeader
        $FieldToScreen = LoadFieldToScreen $FieldToScreenFile
        $ProjectToScreen = LoadProjectToScreen $ProjectToScreenFile
        $ScreenToWorkflow = LoadScreenToWorkflow $ProjectToWorkflowFile $WorkflowDetailsFile
        try {
            $FieldList = GetChecklistCustomFields $AuthHeader
            WriteCSV $Csv @("Field Name", "Field ID", "Field-Projects", "Field-IssueTypes", "Field-in-Screens", "Screen-in-Projects", "Screen-in-Workflows") $True
            foreach ($Field in $FieldList) {
                $FieldName = $Field.name
                $FieldId = $Field.id
                # Custom field used in projects in project contexts
                $FieldProjects = ''
                if ($Field.isAllProjects) {
                    $FieldProjects += "`nAll"
                }
                if ($Field.projectIds) {
                    foreach ($ProjectId in $Field.projectIds) {
                        $ProjectName = $ProjectMap["${ProjectId}"][0]
                        $ProjectKey = $ProjectMap["${ProjectId}"][1]
                        $FieldProjects += "`n" + $ProjectName + ":" + $ProjectKey + ":" + $ProjectId
                    }
                }
                if ($FieldProjects) {
                    $FieldProjects = $FieldProjects.Substring(1)
                } 
                if (-not $FieldProjects) {
                    $FieldProjects = ' '
                }
                # Custom field used in issue types in project contexts
                $FieldIssueTypes = ''
                if ($Field.issueTypeIds) {
                    foreach ($IssueType in $Field.issueTypeIds) {
                        $FieldIssueTypes += "`n" + $IssueTypeMap["${IssueType}"] + ':' + $IssueType
                    }
                    if ($FieldIssueTypes) {
                        $FieldIssueTypes = $FieldIssueTypes.Substring(1)
                    } 
                }
                if (-not $FieldIssueTypes) {
                    $FieldIssueTypes = ' '
                }
                # Custom field used in screens
                $FieldScreens = ''
                $ScreenMap = @{}
                if ($FieldToScreen["${FieldId}"]) {
                    foreach ($ScreenItem in $FieldToScreen["${FieldId}"].GetEnumerator()) {
                        $FieldScreens += "`n" + $ScreenItem.Value + ':' + $ScreenItem.Name
                        $ScreenMap[$ScreenItem.Name] = $ScreenItem.Value
                    }
                    if ($FieldScreens) {
                        $FieldScreens = $FieldScreens.Substring(1)
                    } 
                }
                if (-not $FieldScreens) {
                    $FieldScreens = ' '
                }
                if ($ScreenMap.Count -gt 0) {
                    foreach ($Screen in $ScreenMap.GetEnumerator()) {
                        $ScreenID = $Screen.Name
                        $ScreenName = $Screen.Value
                        # Screens used in projects
                        $ProjectScreens = ''
                        if ($ProjectToScreen["${ScreenID}"]) {
                            foreach ($Item in $ProjectToScreen["${ScreenID}"]) {
                                $ProjectScreens += "`n" + $Item
                            }
                            if ($ProjectScreens) {
                                $ProjectScreens = $ProjectScreens.Substring(1)
                            } 
                        }
                        # Screens used in workflows (and then projects)
                        $WorkflowScreens = ''
                        if ($ScreenToWorkflow["${ScreenID}"]) {
                            foreach ($Item in $ScreenToWorkflow["${ScreenID}"]) {
                                $WorkflowScreens += "`n" + $Item
                            }
                            if ($WorkflowScreens) {
                                $WorkflowScreens = $WorkflowScreens.Substring(1)
                            } 
                        }
                        # Write output
                        if (-not $ProjectScreens) {
                            $ProjectScreens = ' '
                        }
                        if (-not $WorkflowScreens) {
                            $WorkflowScreens = ' '
                        }
                        WriteCSV $Csv @($FieldName, $FieldId, $FieldProjects, $FieldIssueTypes, $($ScreenName + ":" + $ScreenID), $ProjectScreens, $WorkflowScreens)
                    } 
                } else {
                    WriteCSV $Csv @($FieldName, $FieldId, $FieldProjects, $FieldIssueTypes, $FieldScreens, ' ', ' ')
                }
            }
        } catch [RestException] { 
            Write-Host "Failed to retrieve custom field list"
        }
    } catch [RestException] { 
        Write-Host "Failed to retrieve project or issue type list"
    }
} elseif ($ExportFieldUseData) {
    $Csv = "ScreenData.${ExportDate}.csv"
    WriteCSV $Csv @('Field ID', 'Screen ID', 'Screen Name') $True
    $Map = GetChecklistFieldsInScreens $AuthHeader
    foreach ($Item in $Map.GetEnumerator()) {
        $FieldId = $Item.Name
        $ScreenMap = $Item.Value
        foreach ($ScreenItem in $ScreenMap.GetEnumerator()) {
            WriteCSV $Csv @($FieldId, $ScreenItem.Name, $ScreenItem.Value)
        }
    }
} elseif ($ExportChecklist) {
    # Fire requests to export checklist fields
    try {
        $FieldList = GetChecklistCustomFields $AuthHeader
        $Session = ServerLogin
        $script:ProgressPreference = 'SilentlyContinue'    # Subsequent calls do not display UI.
        foreach ($Field in $FieldList) {
            $FieldName = $Field.name
            $FieldId = $Field.id
            $StrippedFieldId = $FieldId -Replace 'customfield_', ''
            # Get list of fieldConfigIds
            try {
                $Resp = Invoke-WebRequest -UseBasicParsing `
                        -Method 'GET' `
                        -Uri "${ServerProtocol}://${ServerDomain}/secure/admin/ConfigureCustomField!default.jspa?customFieldId=${StrippedFieldId}" `
                        -WebSession $Session
                # Get fieldConfigId
                $HRefList = $Resp | Select -ExpandProperty links | Where-Object {$_.class -eq 'aui-button'}
                $FieldConfigIdList = [System.Collections.ArrayList]::new()
                foreach ($HRef in $HRefList) {
                    $Link = $HRef.href -Replace 'ExportChecklist\.jspa\?fieldConfigId=', ''
                    [void] $FieldConfigIdList.Add($Link)
                }
                foreach ($FieldConfigId in $FieldConfigIdList) {
                    try {
                        # Get atl_token and check for existing files
                        $Resp = Invoke-WebRequest -UseBasicParsing `
                                -Method 'GET' `
                                -Uri "${ServerProtocol}://${ServerDomain}/secure/admin/ExportChecklist.jspa?fieldConfigId=${FieldConfigId}" `
                                -WebSession $Session
                        $atl_token = $Resp `
                                    | Select -ExpandProperty inputfields `
                                    | Where-Object -Property 'name' -Value 'atl_token' -EQ `
                                    | Select -ExpandProperty value
                        # Trigger export
                        $Body = @{
                            "atl_token" = $atl_token; 
                            "fieldConfigId" = $FieldConfigId;
                        }
                        $Resp = Invoke-WebRequest -UseBasicParsing `
                                -Method 'POST' `
                                -Uri "${ServerProtocol}://${ServerDomain}/secure/admin/ExportChecklist!Export.jspa" `
                                -Body $Body `
                                -WebSession $Session
                        Write-Host "${FieldName} (${FieldId}) ${FieldConfigId}: Exported"
                    } catch {
                        Write-Host "${FieldName} (${FieldId}) ${FieldConfigId}: Failed to export field config" 
                    }
                }
            } catch {
                Write-Host "${FieldName} (${FieldId}): Failed to retrieve field config id list" 
            }
        }
    } catch [RestException] { 
        Write-Host "Failed to retrieve custom field list"
    } finally {
        $script:ProgressPreference = 'Continue'            # Subsequent calls do display UI.
    }
} elseif ($CompareSettings) {
    # Create temp folder
    $TempDir = CreateTempFolder
    New-Item -ItemType Directory -Path $TempDir | Out-Null
    # Unzip to temp folder (requires 7-Zip, otherwise load a .NET library)
    $ArchiveList = Get-ChildItem -Path $ComparePath -Filter *.gz
    foreach ($Archive in $ArchiveList) {
        $MatchInfo = $Archive.Name | Select-String -Pattern '(.+)\.gz'
        $OutputPath = $Archive.Name + '.decompressed'
        if ($MatchInfo) {
            $OutputPath = $MatchInfo.matches.groups[1].Value
        }
        GUnzip $Archive.FullName $($TempDir + '\' + $OutputPath)
    }
    # Compare Checklist for Jira export files, grab settings used and write to CSV file
    $Output = "CompareSettings.${ExportDate}.csv"
    $Col_CustomFieldID = 'Custom Field ID'
    $Col_FieldConfigId = 'Field Config ID'
    $Col_Name = 'Name'
    $Col_Desc = 'Description'
    $Col_Strikethrough = 'Enable Strikethrough'
    $Col_Status = 'Enable Status'
    $Col_LocalItems = 'Enable Local Items'
    $Col_ShowMoreCount = 'Show More Count'
    $Col_LockOnResolution = 'Lock on Resolution'
    $Col_ReporterCanEdit = 'Reporter can Edit'
    $Col_AllCanComplete = 'All can Complete'
    $Col_AllowMandatory = 'Allow Mandatory'
    $Col_ValidateOptions = 'Validate Options'
    $Col_EditRoleIDs = 'Edit Role IDs'
    $Col_StatusList = 'Status List'
    $Headers = @(
        $Col_CustomFieldID, 
        $Col_FieldConfigId,
        $Col_Name,
        $Col_Desc,
        $Col_Strikethrough,
        $Col_Status,
        $Col_LocalItems,
        $Col_ShowMoreCount,
        $Col_LockOnResolution,
        $Col_ReporterCanEdit,
        $Col_AllCanComplete,
        $Col_AllowMandatory,
        $Col_ValidateOptions,
        $Col_EditRoleIDs,
        $Col_StatusList
    )
    WriteCSV $Output $Headers $True
    $FileList = Get-ChildItem -Path $TempDir -Filter 'customfield_*-1'
    foreach ($File in $FileList) {
        # Extract custom field ID and field config ID
        Write-Host "Processing file: ${File}"
        $MatchInfo = $File | Out-String | Select-String -Pattern 'customfield_([0-9]+)-([0-9]+)-1'
        if ($MatchInfo) {
            $CsvEntry = @{}
            $CsvEntry[$Col_CustomFieldID] = $MatchInfo.matches.groups[1].Value
            $CsvEntry[$Col_FieldConfigId] = $MatchInfo.matches.groups[2].Value
            # Parse content as JSON
            $Content = Get-Content -Raw "${TempDir}\${File}"
            $Json = $Content | ConvertFrom-Json
            foreach ($Item in $Json) {
                if ($Item.type -eq 'statuses') {
                    $StatusList = ''
                    if ($Item.payload) {
                        foreach ($Status in $Item.payload) {
                            $StatusList += ',' + $Status.name + ' (' + $Status.status_id + ')'
                        }
                        $StatusList = $StatusList.Substring(1)
                    }
                    if (-not $StatusList) {
                        $StatusList = '=Empty='
                    }
                    $CsvEntry[$Col_StatusList] = $StatusList 
                } elseif ($Item.type -eq 'checklist_definition') {
                    $CsvEntry[$Col_Name] = $Item.payload.name
                    $CsvEntry[$Col_Desc] = $Item.payload.description
                    $CsvEntry[$Col_Strikethrough] = $Item.payload.strikethrough
                    $CsvEntry[$Col_Status] = $Item.payload.enable_statuses
                    $CsvEntry[$Col_LocalItems] = $Item.payload.enable_local_items
                    $CsvEntry[$Col_ShowMoreCount] = $Item.payload.show_more_count
                    $CsvEntry[$Col_LockOnResolution] = $Item.payload.lock_on_resolution
                    $CsvEntry[$Col_ReporterCanEdit] = $Item.payload.reporter_can_edit
                    $CsvEntry[$Col_AllCanComplete] = $Item.payload.all_can_complete
                    $CsvEntry[$Col_AllowMandatory] = $Item.payload.allow_mandatory
                    $CsvEntry[$Col_ValidateOptions] = $Item.payload.validate_options
                    $EditRoleIds = ''
                    if ($Item.payload.edit_role_ids) {
                        foreach ($Id in $Item.payload.edit_role_ids) {
                            $EditRoleIds += ',' + $Id
                        }
                        $EditRoleIds = $EditRoleIds.Substring(1)
                    }
                    if (-not $EditRoleIds) {
                        $EditRoleIds = '=Empty='
                    }
                    $CsvEntry[$Col_EditRoleIDs] = $EditRoleIds 
                }
            } # For each payload
            # Write CSV
            $CsvData = [System.Collections.ArrayList]::new()
            foreach ($Header in $Headers) {
                [void] $CsvData.Add($CsvEntry[$Header])
            }
            WriteCSV $Output $CsvData
        }
    } # for each file
} elseif ($FormatTemplates) {   
    $Output = "ChecklistTemplates.${ExportDate}.csv"
    # Create temp folder
    $TempDir = CreateTempFolder
    New-Item -ItemType Directory -Path $TempDir | Out-Null
    # Unzip to temp folder (requires 7-Zip, otherwise load a .NET library)
    $ArchiveList = Get-ChildItem -Path $ChecklistExportDir -Filter *.gz
    foreach ($Archive in $ArchiveList) {
        $MatchInfo = $Archive.Name | Select-String -Pattern '(.+)\.gz'
        $OutputPath = $Archive.Name + '.decompressed'
        if ($MatchInfo) {
            $OutputPath = $MatchInfo.matches.groups[1].Value
        }
        GUnzip $Archive.FullName $($TempDir + '\' + $OutputPath)
    }
    # Parse export files, extract the templates and local items.
    
    # Get Jira object relationships
    $FieldToScreen = LoadFieldToScreen $FieldToScreenFile # Map of Field ID = Map of Screen ID = Screen Name.
    $ProjectToScreen = LoadProjectToScreen $ProjectToScreenFile # Map of Screen ID = Project (Name:Key:ID) = IssueType
    $ScreenToWorkflow = LoadScreenToWorkflow $ProjectToWorkflowFile $WorkflowDetailsFile # Map of Screen ID = WorkflowName = (ProjectName:Key:ID) = IssueType)
    $FieldMaps = GetChecklistCustomField_IDNameMaps $AuthHeader 
    $FieldIDMap = $FieldMaps[0] # Key: Field ID, Value: Field Name
    $FieldNameMap = $FieldMaps[1] # Key: Field Name, Value: Field ID
    $ProjectMap = GetProjects $AuthHeader # Key:Project ID, Value: [Name, Key] 
    $IssueTypeMap = GetIssueTypes $AuthHeader # Map of ID = Name
    
    $TemplateList = [System.Collections.ArrayList]::new() # Item is a list of TemplateData class
    
    $FileList = Get-ChildItem -Path $TempDir
    foreach ($File in $FileList) {
        $MatchInfo = $File | Out-String | Select-String -Pattern 'customfield_([0-9]+)-([0-9]+)-([0-9]+)'
        if ($MatchInfo) {
            Write-Host "Processing file: ${File}"
            $CustomFieldId = $MatchInfo.matches.groups[1].value
            $ContextId = $MatchInfo.matches.groups[2].value
            $Content = Get-Content -Raw $($TempDir + '\' + $File)
            $Json = $Content | ConvertFrom-Json
            
            # Export format has updated
            if ($Json.type -eq 'manifest') {
                # New format
                $FieldId = 'customfield_' + $Json.fieldConfig.customFieldId
                $FieldName = $FieldIDMap["${FieldId}"]
                $ContextName = $Json.fieldConfig.name
                $NewTemplate = [TemplateData]::new($FieldId, $FieldName, $ContextName)
                $NewTemplate.TemplateText = ConvertChecklistTemplate $Json.globalItems $True
                if ($FieldToScreen["${FieldId}"]) {
                    $ScreenMap = $FieldToScreen["${FieldId}"]
                    foreach ($Screen in $ScreenMap.GetEnumerator()) {
                        $ScreenId = $Screen.Name
                        $ScreenName = $Screen.Value
                        # Get projects using screen
                        if ($ProjectToScreen["${ScreenId}"]) {
                            $ProjectData = $ProjectToScreen["${ScreenId}"]
                            $MatchInfo = $ProjectData | Out-String | Select-String -Pattern '\((.+):(.+):([0-9]+)\) = (.+)'
                            if ($MatchInfo) {
                                $ProjectName = $MatchInfo.matches.groups[1]
                                $IssueType = $MatchInfo.matches.groups[4]
                                if (-not $NewTemplate.Projects["${ProjectName}"]) {
                                    $NewTemplate.Projects["${ProjectName}"] = [System.Collections.Generic.HashSet[string]]::new()
                                }
                                [void] $NewTemplate.Projects["${ProjectName}"].Add($IssueType)
                            }
                        }
                        # Get workflows using screen
                        if ($ScreenToWorkflow["${ScreenId}"]) {
                            $WorkflowData = $ScreenToWorkflow["${ScreenId}"]
                            $MatchInfo = $WorkflowData | Out-String | Select-String -Pattern '(.+) = \((.+):(.+):([0-9]+)\) = (.+)'
                            if ($MatchInfo) {
                                $WorkflowName = $MatchInfo.matches.groups[1]
                                $ProjectName = $MatchInfo.matches.groups[2]
                                $IssueType = $MatchInfo.matches.groups[5]
                                if (-not $NewTemplate.Projects["${ProjectName}"]) {
                                    $NewTemplate.Projects["${ProjectName}"] = [System.Collections.Generic.HashSet[string]]::new()
                                }
                                [void] $NewTemplate.Projects["${ProjectName}"].Add($IssueType)
                            }
                        }
                    }
                } else {
                    Write-Host "Error: Cannot find field id ${FieldId}"
                }
                [void] $TemplateList.Add($NewTemplate)
            } else {
                # Old format
                foreach ($Item in $Json) {
                    # Check type, locate checklist_definition, manifest and templates
                    if ($Item.type -eq 'checklist_definition') {
                        $FieldName = $Item.payload.name
                        $ContextName = $Item.payload.description
                        # Get screens containing field
                        if ($FieldNameMap["${FieldName}"]) {
                            $FieldId = $FieldNameMap["${FieldName}"]
                            # Store global_items as template
                            $NewTemplate = [TemplateData]::new($FieldId, $FieldName, $ContextName)
                            $NewTemplate.TemplateText = ConvertChecklistTemplate $Item.payload.global_items
                            if ($FieldToScreen["${FieldId}"]) {
                                $ScreenMap = $FieldToScreen["${FieldId}"]
                                foreach ($Screen in $ScreenMap.GetEnumerator()) {
                                    $ScreenId = $Screen.Name
                                    $ScreenName = $Screen.Value
                                    # Get projects using screen
                                    if ($ProjectToScreen["${ScreenId}"]) {
                                        $ProjectData = $ProjectToScreen["${ScreenId}"]
                                        $MatchInfo = $ProjectData | Out-String | Select-String -Pattern '\((.+):(.+):([0-9]+)\) = (.+)'
                                        if ($MatchInfo) {
                                            $ProjectName = $MatchInfo.matches.groups[1]
                                            $IssueType = $MatchInfo.matches.groups[4]
                                            if (-not $NewTemplate.Projects["${ProjectName}"]) {
                                                $NewTemplate.Projects["${ProjectName}"] = [System.Collections.Generic.HashSet[string]]::new()
                                            }
                                            [void] $NewTemplate.Projects["${ProjectName}"].Add($IssueType)
                                        }
                                    }
                                    # Get workflows using screen
                                    if ($ScreenToWorkflow["${ScreenId}"]) {
                                        $WorkflowData = $ScreenToWorkflow["${ScreenId}"]
                                        $MatchInfo = $WorkflowData | Out-String | Select-String -Pattern '(.+) = \((.+):(.+):([0-9]+)\) = (.+)'
                                        if ($MatchInfo) {
                                            $WorkflowName = $MatchInfo.matches.groups[1]
                                            $ProjectName = $MatchInfo.matches.groups[2]
                                            $IssueType = $MatchInfo.matches.groups[5]
                                            if (-not $NewTemplate.Projects["${ProjectName}"]) {
                                                $NewTemplate.Projects["${ProjectName}"] = [System.Collections.Generic.HashSet[string]]::new()
                                            }
                                            [void] $NewTemplate.Projects["${ProjectName}"].Add($IssueType)
                                        }
                                    }
                                }
                            }
                        } else {
                            Write-Host "Error: Cannot find field id for field ${FieldName}"
                        }
                        [void] $TemplateList.Add($NewTemplate)
                    } elseif ($Item.type -eq 'templates') {
                        foreach ($Template in $Item.templates) {
                            # Store as a template
                            $NewTemplate = [TemplateData]::new('N/A', 'N/A', $Template.name)
                            $NewTemplate.TemplateText = ''
                            $NewTemplate.TemplateText = ConvertChecklistTemplate $Template.items
                            [void] $TemplateList.Add($NewTemplate)
                        }
                    }
                }
            }
        }
    }
    
    # Output CSV file
    # Count no. of columns required
    $AdditionalColCount = 0
    foreach ($TemplateData in $TemplateList) {
        $Length = $TemplateData.TemplateText.Length
        if ($Length -gt 32767) {
            $ColCount = 0
            $Length -= 32767
            while ($Length -gt 0) {
                $Length -= 32767
                $ColCount += 1
            }
            if ($AdditionalColCount -lt $ColCount) {
                $AdditionalColCount = $ColCount
            }
        }
    }
    $AdditionalColumns = [System.Collections.ArrayList]::new()
    if ($ColCount -gt 0) {
        1 .. $ColCount | %{ [void] $AdditionalColumns.Add('Checklist Template') }   
    }
    WriteCSV $Output @('Checklist Custom Field ID', 'Checklist Custom Field Name', 'Context Name', 'Project/Issue Type', 'Checklist Template'; $AdditionalColumns) $True
    foreach ($TemplateData in $TemplateList) {
        $Associations = ''
        foreach ($Project in $TemplateData.Projects.GetEnumerator()) {
            $Associations += "`n" + $Project.Name + ':'
            $IssueTypes = ''
            foreach ($IssueType in $Project.Value) {
                $IssueTypes += ';' + $IssueType.Trim()
            }
            if ($IssueTypes) {
                $IssueTypes = $IssueTypes.Substring(1)
            } else {
                $IssueTypes = ' '
            }
            $Associations += $IssueTypes
        }
        if ($Associations) {
            $Associations = $Associations.Substring(1)
        } else {
            $Associations = ' '
        }
        if ($TemplateData.TemplateText.Length -gt 32767) {
            # Split template text
            $TemplateChunks = [System.Collections.ArrayList]::new()
            $Text = $TemplateData.TemplateText
            while ($Text.Length -gt 32767) {
                [void] $TemplateChunks.Add($Text.Substring(0, 32767))
                $Text = $Text.Substring(32767)
            }
            [void] $TemplateChunks.Add($Text)
            WriteCSV $Output @($TemplateData.CustomFieldId, $TemplateData.CustomFieldName, $TemplateData.TemplateName, $Associations; $TemplateChunks)
        } else {
            WriteCSV $Output @($TemplateData.CustomFieldId, $TemplateData.CustomFieldName, $TemplateData.TemplateName, $Associations, $TemplateData.TemplateText)
        }
    }

    Write-Host "Output written to ${Output}"
    Write-Host 
    Write-Host 'IMPORTANT NOTE: '
    Write-Host 'Do not open the CSV file in Excel by double-clicking. '
    Write-Host 'Excel will corrupt the format of the file due to the amount of newlines in a single cell. '
    Write-Host 
    Write-Host 'Instead, follow these steps: '
    Write-Host '1. Open a blank workbook in Excel. '
    Write-Host '2. In Ribbon, click Data | From Text/CSV. '
    Write-Host '3. Select CSV file. '
    Write-Host '4. Click Transform Data button at the bottom. '
    Write-Host '5. In Ribbon, click Use First Row as Headers.'
    Write-Host '6. In Ribbon, click Close & Load.'
    Write-Host 
}

if ($TempDir) {
    # Delete temp folder
    Remove-Item -Recurse -Force $TempDir
}