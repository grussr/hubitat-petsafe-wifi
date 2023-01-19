/*
  PetSafe feeder

  Uses petsafe api to control Smartfeed wi-fi feeders

  By Ryan Gruss

  Based on code written by Dominick Meglio and Niklas Gustafsson
*/

metadata
{
    definition(name: "PetSafe Pet Feeder", namespace: "grussr", author: "Ryan Gruss")
    {
        capability "Refresh"
        capability "Switch"
        capability "PowerSource"
        capability "Consumable"
        capability "SignalStrength"
        capability "Battery"
        capability "VoltageMeasurement"
        
        attribute "state", "string"
        attribute "connection_status", "number"
        attribute "hopperStatus", "enum"
        attribute "slow_feed", "bool"
        attribute "child_lock", "bool"
        attribute "feeding_schedule", "JSON_OBJECT"

        attribute "lastFeedingTime", "number"
        attribute "lastMessageCheck", "number"

        command "setSchedule", ["JSON_OBJECT"]        
        command "manualFeed", [[name:"amount",type:"ENUM", description:"Amount in Cups",
                               constraints: ["1/8", "1/4", "3/8", "1/2", "5/8" , "3/4", "7/8", "1",
                                            "1 1/8", "1 1/4", "1 3/8", "1 1/2", "1 5/8" , "1 3/4", "1 7/8", "2",
                                            "2 1/8", "2 1/4", "2 3/8", "2 1/2", "2 5/8" , "2 3/4", "2 7/8", "3",
                                            "3 1/8", "3 1/4", "3 3/8", "3 1/2", "3 5/8" , "3 3/4", "3 7/8", "4"]
                               ], [name:"slow_feed", type:"ENUM", description:"Slow feed?", constraints: ["false", "true"]]]
        
        command "defaultFeed"
        command "setChildLock", [[name: "lockEnabled", type: "ENUM", constraints: ["false", "true"]]]


    }
}

preferences {
    input "defaultFeedAmount", "enum", title: "Default Feeding Amount (in Cups)" , 
        options: ["1/8", "1/4", "3/8", "1/2", "5/8" , "3/4", "7/8", "1",
                "1 1/8", "1 1/4", "1 3/8", "1 1/2", "1 5/8" , "1 3/4", "1 7/8", "2",
                "2 1/8", "2 1/4", "2 3/8", "2 1/2", "2 5/8" , "2 3/4", "2 7/8", "3",
                "3 1/8", "3 1/4", "3 3/8", "3 1/2", "3 5/8" , "3 3/4", "3 7/8", "4"]
    input "defaultSlowFeed", "bool", title: "Default to slow feeding", defaultValue: false
    input name: "debugOutput", type: "bool", title: "Enable debug logging", defaultValue: false
}

import groovy.transform.Field
import groovy.json.JsonSlurper

static @Field apiUrl = "https://platform.cloud.petsafe.net/"

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

def initialize()
{    
    refresh()
}

def refresh()
{
    parent?.refreshFromParent(device)
}

def updated()
{
    if (settings?.debugOutput) {
        logDebug "Notice: Turning off debug in 30 minutes"
        runIn(1800, logDebugOff)
    }
}

def manualFeed(amount, slow_feed) {
    logDebug "amount is ${amount}, slow_feed is ${slow_feed}"
    def id = device.deviceNetworkId
    def slow_bool = slow_feed.toBoolean()
    def amountToFeed = fracToFloat(amount)
    def endpoint = "${apiUrl}smart-feed/feeders/${id}/meals"
    
    logDebug "feed is ${amountToFeed}, device is ${device}, parent is ${parent}"
	def payload = [
			"amount": amountToFeed * 8,
			"slow_feed": slowFeed ?: false
		]
    parent.sendAuthenticatedPost(endpoint, payload) 
        { resp ->
			result = true
		}
    result = false
}

def defaultFeed() {
    return manualFeed(defaultFeedAmount, defaultSlowFeed)
}

def parse(body)
{
    if(!body) { 
        logDebug "Nobody"
        return 
    }
    logDebug "In parse, body is ${body}"
    sendEvent(name: "switch", value: (!body.settings.paused).toString()?.toLowerCase())
    
    if(null != body.voltage)
    {
        voltage = body.voltage.toFloat()?.round(2)
        sendEvent(name: "voltage", value: voltage)
    }
    
    switch (body.is_food_low) {
        case 0: // full
            sendEvent(name: "hopperStatus", value: "full")
	        sendEvent(name: "consumableStatus", value: "good")
            break
        case 1: // low
            sendEvent(name: "hopperStatus", value: "low")
            sendEvent(name: "consumableStatus", value: "replace")
            break
        case 2: // empty
            sendEvent(name: "hopperStatus", value: "empty")
            sendEvent(name: "consumableStatus", value: "missing")
            break
    }
    sendEvent(name: "voltage", value: (body.battery_voltage.toFloat() / 4850).round(2))
    sendEvent(name: "connection_status", value: body.connection_status.toString()) 
    if (body.is_adapter_installed) {
	    sendEvent(name: "powerSource", value: "mains")
    }
	else if (body.is_batteries_installed) {
        sendEvent(name: "powerSource", value: "battery")
    }		
    def voltage = body.battery_voltage.toInteger()
    if (voltage >= 100) {
        if (voltage > 29100) {
            sendEvent(name: "battery", value: 100)
        }
        else {
            sendEvent(name: "battery", value: (int)(((voltage - 23000)/(29100-23000))*100))
        }
    }
    sendEvent(name: "lqi", value: body.network_snr)
    sendEvent(name: "rssi", value: body.network_rssi) 
    sendEvent(name: "slow_feed", value: body.settings.slow_feed)
	sendEvent(name: "child_lock", value: body.settings.child_lock)
    def feedingSchedule = []
	body.schedules.each { 
        feedingSchedule << [ time: it.time, amount: it.amount/8]
    }
    sendEvent(name: "feeding_schedule", value: feedingSchedule)	 
    updateFeedings()
}

def updateFeedings() 
{
    def recentFeedings = apiGetRecentFeedings()
    def epochNow = (int)(now()/1000)
    def lastMessage = device.currentValue("lastMessageCheck")
    if (lastMessage.toString().contains("T") || lastMessage > 9999999999) {
        sendEvent(name: "lastMessageCheck", value: epochNow)
        lastMessage = epochNow
    }
    for (feeding in recentFeedings) {
        def msgEpoch = null
        try {
            msgEpoch = feeding.payload?.time ?: (int)(Date.parse("yyyy-MM-dd HH:mm:ss", feeding.created_at, TimeZone.getTimeZone('UTC')).getTime()/1000)
        }
        catch (e) {
            msgEpoch = epochNow
        }
        logDebug "Feeding Message: ${feeding.message_type} ${msgEpoch} ${lastMessage}"

        if (msgEpoch > lastMessage) {
            logDebug "msgEpoch ${msgEpoch} lastMessage ${lastMessage}"
            switch (feeding.message_type) {
                case "FEED_ERROR_MOTOR_CURRENT":
                case "FEED_ERROR_MOTOR_SWITCH":
                    sendEvent(name: "consumableStatus", value: "maintenance_required")
                    sendEvent(name: "state", value: "motor_error")
                break
                case "FEED_DONE":
                    sendEvent(name: "lastFeedingTime", value: feeding.payload.time)
                    sendEvent(name: "state", value: "feeding_complete")
                break
                case "FOOD_GOOD":
                    sendEvent(name: "hopperStatus", value: "full")
                    sendEvent(name: "consumableStatus", value: "good")
                    break
                case "FOOD_LOW":
                    sendEvent(name: "hopperStatus", value: "low")
                    sendEvent(name: "consumableStatus", value: "replace")
                    break
                case "FOOD_EMPTY":
                    sendEvent(name: "hopperStatus", value: "empty")
                    sendEvent(name: "consumableStatus", value: "missing")
                    break
                case "WILL_MESSAGE":
                    // nothing going on here
                    break
            }
            sendEvent(name: "lastMessageCheck", value: msgEpoch)
            lastMessage = msgEpoch
        }
    }
}

def apiGetRecentFeedings(numDays = 2) {
    def id = device.deviceNetworkId
    def endpoint = "${apiUrl}smart-feed/feeders/${id}/messages?days=${numDays}"
    def result = null
	parent.sendAuthenticatedGet(endpoint) 
        { resp ->
		result = resp.data
	}
    return result

}

//[{time=6:30, amount=0.375}, {time=18:00, amount=0.375}]
def setSchedule(schedule) {
	try
	{
		def scheduleJson =  new JsonSlurper().parseText(schedule) 
		scheduleJson.each {
			def t = timeToday(it.time)
			it.time = t.hours + ":" + t.minutes
			def cups = it.amount*8
			
			if (it.amount < 0.125 || it.amount > 1 || [1,2,3,4,5,6,7,8].find {v -> v == cups} == null) {
				log.error "Invalid schedule specified"
				return
			}
		}
	}
	catch (e) {
		log.error "Invalid schedule specified"
		return
	}
	def feeder = device.deviceNetworkId
	def endpoint = "${apiUrl}smart-feed/feeders/${feeder}/schedules"
    def result = null

	parent.sendAuthenticatedGet(params) { resp ->
		for (currentFeeding in resp.data) {
			def newItem = scheduleJson.find { it.time == currentFeeding.time }
			if (newItem == null) {
				parent.sendAuthenticatedDelete("${apiUrl}smart-feed/feeders/${feeder}/schedules/${currentFeeding.id}")
			}
			else if (newItem != null && newItem.amount != currentFeeding.amount/8) {
				def newPayload = [
						amount: newItem.amount*8,
						time: newItem.time
					]
                parent.sendAuthenticatedPut("${apiUrl}smart-feed/feeders/${feeder}/schedules/${currentFeeding.id}",
                                           newPayload)
			}
		}
		for (newItem in scheduleJson) {
			if (!resp.data.find { it.time == newItem.time}) {              
				def newPayload = [
						amount: newItem.amount*8,
						time: newItem.time
					]
                parent.sendAuthenticatedPost("${apiUrl}smart-feed/feeders/${feeder}/schedules",
                                     newPayload)
			}
		}
	}
	return result
}

// convert a fraction and/or whole number to a float value
// whole number must be separated by a space, fraction with /
// ex: fracToFloat("1 3/4") -> 1.75
def fracToFloat(input)
{
    def wholeNumber, numerator, denominator
    def fractionalSplit, fractionSplit
    
    fractionalSplit = input.split(" ")
    if (fractionalSplit.length == 1 && input.indexOf("/") > -1) {
        wholeNumber = 0
        fractionSplit = fractionalSplit[0].split("/")
    }
    else if (fractionalSplit.length == 1 && input.indexOf("/") == -1) {
        wholeNumber = fractionalSplit[0].toInteger()
        fractionSplit = "0/1".split("/")    
    }
    else {
        wholeNumber = fractionalSplit[0].toInteger()
        fractionSplit = fractionalSplit[1].split("/")
    }

    numerator = fractionSplit[0].toInteger()
    denominator = fractionSplit[1].toInteger()
    
    return (numerator / denominator) + wholeNumber
}

def on()
{
    // this will turn scheduling on
    def id = device.deviceNetworkId
    parent.sendAuthenticatedPut("${apiUrl}smart-feed/feeders/${id}/settings/paused", ["value": false]) 
        { resp ->
            logDebug resp.data
            refresh()
        }
}

def off()
{
    // this will turn scheduling off
    def id = device.deviceNetworkId
    parent.sendAuthenticatedPut("${apiUrl}smart-feed/feeders/${id}/settings/paused", ["value": true])
        { resp ->
            logDebug resp.data
            refresh()
        }
}

def setChildLock(lockEnabled)
{
    def id = device.deviceNetworkId
    parent.sendAuthenticatedPut("${apiUrl}smart-feed/feeders/${id}/settings/child_lock", ["value": lockEnabled.toBoolean()])
        { resp ->
            logDebug resp.data
            refresh()
        }    
}
