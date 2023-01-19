/*
  PetSafe system 

  Uses petsafe api to get information about petsafe devices.

  for now only controls Smartfeed wi-fi feeders

  By Ryan Gruss

  Based on code written by Dominick Meglio and Niklas Gustafsson
*/
metadata {
    definition(name: "PetSafe System", namespace: "grussr", author: "Ryan Gruss", importUrl: "") {
        capability "Actuator"
        capability "Refresh"
        
        command "setEmail", ["setEmailAddress"]
        command "loginWithCode", ["codeFromEmail"]
        command "logout"
        command "apiGetDevices"
        
        attribute "loginStatus", "string"   
        attribute "accessToken", "string"
        attribute "region", "string"
        attribute "session", "string"
        attribute "userName", "string"
        attribute "expiration", "number"
        attribute "refreshToken", "string"
        attribute "idToken", "string"
    }
}

preferences {
    section
    {
        input "refreshInterval", "number", title: "Polling refresh interval in minutes (caution: 1000 operation limit per day)", defaultValue: 5
        input name: "debugOutput", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

import groovy.transform.Field
static @Field gComponentFlag = "gComponent"
static @Field apiUrl = "https://platform.cloud.petsafe.net/"
static @Field apiDirectory = "https://directory.cloud.petsafe.net"

def logDebug(msg) {
    if (debugOutput)
    {
        log.debug msg
    }
}

void logDebugOff() {
  //
  // runIn() callback to disable "Debug" logging after 30 minutes
  // Cannot be private
  //
  if (settings?.debugOutput) device.updateSetting("debugOutput", [type: "bool", value: false]);
}

def installed() {
    sendEvent(name: "loginStatus", value: "notloggedin")
    sendEvent(name: "region", value: apiGetRegion())
}

def initialize() {
    if (device.currentValue("loginStatus") == "loggedin") {
        refresh()
    }
}

def updated() {
    configure()
    if (settings?.debugOutput) {
        logDebug "Notice: Turning off debug in 30 minutes"
        runIn(1800, logDebugOff)
    }
    unschedule(apiGetDevices)
    schedule("0 0/${refreshInterval} * * * ?", apiGetDevices)
}

def configure() {
    state.clear
    initialize()
}

def refresh() {
    apiGetDevices()
}

def uninstalled() {
    unschedule()
    for(child in getChildDevices())
    {
        deleteChildDevice(child.deviceNetworkId)
    }
}

def setEmail(email) 
{
    if (device.currentValue("loginStatus") == "loggedin") {
        log.error "Currently logged in.  To change users click Logout and try again."
        return
    }
    if (email == "") {
        log.error "Email cannot be empty!"
        return
    }
    
    sendEvent(name: "loginStatus", value: "waitingForCode")
    def authData = apiAuthenticate()
    sendEvent(name: "session", value: authData?.Session)
	sendEvent(name: "userName", value: authData?.ChallengeParameters.USERNAME)
	
}

def loginWithCode(otp) {
    if (device.currentValue("loginStatus") != "waitingForCode") 
    {
        log.error "Cannot enter code without email address.  Enter email address and click Set Email, then enter the provided code"
        return
    }
    logDebug otp
    return apiGetTokens(otp)
}

def logout() {
    sendEvent(name: "loginStatus", value: "notloggedin")
    sendEvent(name: "session", value: "")
    sendEvent(name: "userName", value: "")
    sendEvent(name: "expiration", value: 0)
	sendEvent(name: "accessToken", value: "")
	sendEvent(name: "refreshToken", value: "")
	sendEvent(name: "idToken", value: "")
    }

def apiGetRegion() {
	def params = [
		uri: "${apiDirectory}/locale",
		contentType: "application/json",
		requestContentType: "application/json"
	] 
	def result = null

	httpGet(params) { resp ->
		result = resp.data?.data?.region
	}
	return result	
}

def apiAuthenticate() {
    def region = device.currentValue("region")
    logDebug "region is ${region}, email is ${emailAddress}"
	def params = [
		uri: "https://cognito-idp.${region}.amazonaws.com/",
		contentType: "application/json",
		requestContentType: "application/json",
		headers: [
			"X-Amz-Target": "AWSCognitoIdentityProviderService.InitiateAuth",
			"Accept-Encoding": "identity",
			"Content-Type": "application/x-amz-json-1.1"
		],
		body: [
			"AuthFlow": "CUSTOM_AUTH",
			"AuthParameters": [
				"USERNAME": emailAddress,
				"AuthFlow": "CUSTOM_CHALLENGE"
			],
			"ClientId": "18hpp04puqmgf5nc6o474lcp2g"
		]
	] 
	def result = null

	httpPost(params) { resp ->
		result = resp.data
	}
    logDebug "authenticate result is ${result}"
	return result
}

def apiGetTokens(emailOtp) {
    def region = device.currentValue("region")
    def session = device.currentValue("session")
    logDebug "region is ${region}, email is ${emailAddress}, code is ${emailOtp}, session is ${session}"

	def params = [
		uri: "https://cognito-idp.${region}.amazonaws.com/",
		contentType: "application/json",
		requestContentType: "application/json",
		headers: [
			"X-Amz-Target": "AWSCognitoIdentityProviderService.RespondToAuthChallenge",
			"Content-Type": "application/x-amz-json-1.1",
			"Accept": "*/*"
		],
		body: [
			"ClientId": "18hpp04puqmgf5nc6o474lcp2g",
    		"ChallengeName": "CUSTOM_CHALLENGE",
			"Session": session,
			"ChallengeResponses": [
				"ANSWER": emailOtp,
				"USERNAME": emailAddress
			]
		]
	] 

	def result = null
	try
	{
		httpPost(params) { resp ->
			result = resp.data
            logDebug "apiGetTokens: result is ${result}"
			sendEvent(name: "expiration", value: now()+(result.AuthenticationResult.ExpiresIn*1000))
			sendEvent(name: "accessToken", value: result.AuthenticationResult.AccessToken)
			sendEvent(name: "refreshToken", value: result.AuthenticationResult.RefreshToken)
			sendEvent(name: "idToken", value: result.AuthenticationResult.IdToken)
            sendEvent(name: "loginStatus", value: "loggedin")
		}
	}
	catch (e) {
		def errResp = e.getResponse()
        logDebug "error in auth ${e}"
		return null
	}
	return result 
}

def apiRefreshAuth() {
	def region = device.currentValue("region")
    def session = device.currentValue("session")
    def expiration = device.currentValue("expiration")
    def refreshToken = device.currentValue("refreshToken")
    
    if (now() < expiration-10000) {
        logDebug "apiRefreshAuth: token not invalid"
		//return null
    }
	def params = [
		uri: "https://cognito-idp.${region}.amazonaws.com/",
		contentType: "application/json",
		requestContentType: "application/json",
		headers: [
			"X-Amz-Target": "AWSCognitoIdentityProviderService.InitiateAuth",
			"Content-Type": "application/x-amz-json-1.1",
			"Accept": "*/*"
		],
		body: [
			"AuthFlow": "REFRESH_TOKEN_AUTH",
			"AuthParameters": [
				"REFRESH_TOKEN": refreshToken
			],
			"ClientId": "18hpp04puqmgf5nc6o474lcp2g"
		]
	] 

	def result = null
	try {
		httpPost(params) { resp ->
			result = resp.data
            logDebug "apiRefreshToken: data is ${result}"
			sendEvent(name: "expiration", value: now()+(result.AuthenticationResult.ExpiresIn*1000))
			sendEvent(name: "accessToken", value: result.AuthenticationResult.AccessToken)
			if (result.AuthenticationResult.RefreshToken != null)
				sendEvent(name: "refreshToken", value: result.AuthenticationResult.RefreshToken)
			sendEvent(name: "idToken", value: result.AuthenticationResult.IdToken)
            sendEvent(name: "loginStatus", value: "loggedin")
		}
	}
	catch (e) {
		def errResp = e.getResponse()
		return null
	}
	return result 
}


def apiGetDevices() {
    def result = null

    result = apiGetFeeders()
    createChildDevices(result)
    return true
}

def apiGetFeeders() 
{
    def result = null

    sendAuthenticatedGet("${apiUrl}smart-feed/feeders") 
    { resp ->
        result = resp.data
    }
    logDebug "apiGetDevices returned ${result}"
    
    return result
}

def apiGetDeviceStatus(deviceId)
{
	def result = null

    sendAuthenticatedGet("${apiUrl}smart-feed/feeders/${deviceId}") 
    { resp ->
        result = resp.data
    }

    return result
}

def Boolean sendAuthenticatedGet(endPoint, Closure closure) 
{
    apiRefreshAuth()
	def idToken = device.currentValue("idToken")
    
 	def params = [
        uri: endPoint,
		contentType: "application/json",
		requestContentType: "application/json",
		headers: [
			"Authorization": idToken
		]
	]

	def result = null
	try
	{
		httpGet(params, closure)
		return true
	}
	catch (e)
	{
        log.Error e.getMessage()
		return false
	}    
}

def sendAuthenticatedPost(endPoint, payload, Closure closure) 
{
 	apiRefreshAuth()
	def idToken = device.currentValue("idToken")
    
    def params = [
		uri: endPoint,
		contentType: "application/json",
		requestContentType: "application/json",
		headers: [
			"Authorization": idToken
		],
		body: payload
	] 
	def result = null

	try
	{
		httpPost(params, closure)
		return true
	}
	catch (e)
	{
        log.Error e.getMessage()
		return false
	}    
}

def Boolean sendAuthenticatedDelete(endPoint, Closure closure) 
{
    apiRefreshAuth()
	def idToken = device.currentValue("idToken")
    
 	def params = [
        uri: endPoint,
		contentType: "application/json",
		requestContentType: "application/json",
		headers: [
			"Authorization": idToken
		]
	]

	def result = null
	try
	{
		httpDelete(params, closure)
		return true
	}
	catch (e)
	{
        log.Error e.getMessage()
		return false
	}    
}

def sendAuthenticatedPut(endPoint, payload, Closure closure) 
{
 	apiRefreshAuth()
	def idToken = device.currentValue("idToken")
    
    def params = [
		uri: endPoint,
		contentType: "application/json",
		requestContentType: "application/json",
		headers: [
			"Authorization": idToken
		],
		body: payload
	] 
	def result = null

	try
	{
		httpPut(params, closure)
		return true
	}
	catch (e)
	{
        log.Error e.getMessage()
		return false
	}    
}

def createChildDevices(items)
{
    logDebug "in create child devices, items is ${items}"
    items.each
    {
        manageChildDevice(it.thing_name, it)
    }        
}

def manageChildDevice(id, details = null)
{
    if(details == null)
    {
        // this is an event, which doesn't include the device type
        //   so, only a device that already exists will work
        return getChildDevice(id)
    }
    
    try
    {
        switch(details.product_name)
        {
            // alias types to more generic virtual device types
            case "SmartFeed_2.0":
                details.type = "Pet Feeder"
                break
            default:
                log.Error "Unknown device ${details.product_name}"
        }
        
        def devDetails = [label: details.settings.friendly_name ?: details.type, isComponent:false, name:id, devType: "PetSafe " + details.type, mac: id]        
        logDebug "devdetails is ${devDetails}"
        // use existing child, or create it
        def child = getChildDevice(id) ?: addChildDevice(devDetails.devType, id, devDetails)
        child.setLabel(devDetails.label)
        child.setName(devDetails.devType)
        
        child.updateDataValue("mac", devDetails.mac)
        child.refresh()
        return child
    }
    catch(com.hubitat.app.exception.UnknownDeviceTypeException e) {
        log.info "Error: unsupported device type.  (id = ${id}, type = ${details.type})"
    }
    catch (Exception e)
    {
        logDebug("parse error: ${e.message}")
    }
}

def refreshFromParent(child)
{
    if(!child)
    {
        return
    }
    
    try
    {
        def id = child.getDeviceNetworkId()
        def respData = apiGetDeviceStatus(id)
        
        if(!respData)
        {
            logDebug "no data from apiGetDeviceStatus"
            return
        }
        
        def dni = child.getDeviceNetworkId()
        logDebug "dni is ${dni}, respdata is ${respData}"
        getChildDevice(child.getDeviceNetworkId())?.parse(respData)
    }
    catch (Exception e)
    {
        logDebug "refreshFromParent() failed: ${e.message}"
    }
}

def getScoopFree()
{
    // not implemented at this time, to come soon!

    /*apiRefreshAuth()
	def idToken = device.currentValue("idToken")
    
 	def params = [
		uri: "${apiUrl}scoopfree/product/product",
		contentType: "application/json",
		requestContentType: "application/json",
		headers: [
			"Authorization": idToken
		]
	]

	def result = null
	httpGet(params) { resp ->
		result = resp.data
	}
    logDebug "getScoopFree returned ${result}" */
}
