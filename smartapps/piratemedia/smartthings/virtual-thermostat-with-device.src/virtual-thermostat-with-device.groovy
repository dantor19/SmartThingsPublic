definition(
    name: "Virtual Thermostat With Device",
    namespace: "piratemedia/smartthings",
    author: "Eliot S.",
    description: "Control a heater in conjunction with any temperature sensor, like a SmartSense Multi.",
    category: "Green Living",
    iconUrl: "https://raw.githubusercontent.com/eliotstocker/SmartThings-VirtualThermostat-WithDTH/master/logo-small.png",
    iconX2Url: "https://raw.githubusercontent.com/eliotstocker/SmartThings-VirtualThermostat-WithDTH/master/logo.png"
)

preferences {
	section("Choose a temperature sensor(s)... (If multiple sensors are selected, the average value will be used)"){
		input "sensors", "capability.temperatureMeasurement", title: "Sensor", multiple: true
	}
	section("Select the AC outlet(s)... "){
		input "outlets", "capability.switch", title: "Outlets", multiple: true
	}
    section("Select the AC outlet(s) that will be excluded when a switch is turned off... "){
		input "exclude_outlets", "capability.switch", title: "Excluded Outlets", required: false, multiple: true
	}
    section("Select the switch which will cause the above outlet to be excluded... "){
		input "exclude_switch", "capability.switch", title: "Exclude Switch", required: false, multiple: false
	}
	section("Only cool when contact isnt open (optional, leave blank to not require contact sensor)..."){
		input "motion", "capability.contactSensor", title: "Contact", required: false
	}
	section("Never go above this temperature: (optional)"){
		input "emergencySetpoint", "decimal", title: "Emergency Temp", required: false
	}
	section("Temperature Threshold (Don't allow heating to go above or bellow this amount from set temperature)") {
		input "threshold", "decimal", "title": "Temperature Threshold", required: false, defaultValue: 1.0
	}
}

def installed()
{
    log.debug "running installed"
    state.deviceID = Math.abs(new Random().nextInt() % 9999) + 1
	state.lastTemp = null
    state.contact = true
    /*def thermostat = createDevice()
    
	subscribe(sensor, "temperature", temperatureHandler)
	if (motion) {
		subscribe(motion, "motion", motionHandler)
	}
    
    subscribe(thermostat, "thermostatSetpoint", thermostatTemperatureHandler)
    subscribe(thermostat, "thermostatMode", thermostatModeHandler)
    thermostat.setVirtualTemperature(sensor.currentValue("temperature"))*/
}

def createDevice() {
    def thermostat
    def label = app.getLabel()
	// Commenting out hub refernce - breaks in several thermostat DHs
    log.debug "create device with id: pmvt$state.deviceID, named: $label" //, hub: $sensor.hub.id"
    try {
        thermostat = addChildDevice("piratemedia/smartthings", "Virtual Thermostat Device", "pmvt" + state.deviceID, null, [label: label, name: label, completedSetup: true])
    } catch(e) {
        log.error("caught exception", e)
    }
    return thermostat
}

def getThermostat() {
	def child = getChildDevices().find {
    	d -> d.deviceNetworkId.startsWith("pmvt" + state.deviceID)
  	}
    return child
}

def uninstalled() {
    deleteChildDevice("pmvt" + state.deviceID)
}

def updated()
{
    log.debug "running updated: $app.label"
	unsubscribe()
    def thermostat = getThermostat()
    if(thermostat == null) {
        thermostat = createDevice()
    }
    state.contact = true
	state.lastTemp = null
	subscribe(sensors, "temperature", temperatureHandler)
	if (motion) {
		subscribe(motion, "contact", motionHandler)
	}
    subscribe(thermostat, "thermostatSetpoint", thermostatTemperatureHandler)
    subscribe(thermostat, "thermostatMode", thermostatModeHandler)
    thermostat.clearSensorData()
    thermostat.setVirtualTemperature(getAverageTemperature())
}

def getAverageTemperature() {
	def total = 0;
    def count = 0;
	for(sensor in sensors) {
    	total += sensor.currentValue("temperature")
        thermostat.setIndividualTemperature(sensor.currentValue("temperature"), count, sensor.label)
        count++
    }
    return total / count
}

def temperatureHandler(evt)
{
    def thermostat = getThermostat()
    thermostat.setVirtualTemperature(getAverageTemperature())
	if (state.contact || emergencySetpoint) {
		evaluate(evt.doubleValue, thermostat.currentValue("thermostatSetpoint"))
        state.lastTemp = evt.doubleValue
	}
	else {
		coolingOff()
        log.debug("cooling off 1")
	}
}

def motionHandler(evt)
{
    def thermostat = getThermostat()
	if (evt.value == "closed") {
    	state.contact = true
		def thisTemp = getAverageTemperature()
		if (thisTemp != null) {
			evaluate(thisTemp, thermostat.currentValue("thermostatSetpoint"))
			state.lastTemp = thisTemp
		}
	} else if (evt.value == "open") {
        log.debug "should turn heating off"
    	state.contact = false
	    coolingOff()
        log.debug("cooling off 2")
	}
}

def thermostatTemperatureHandler(evt) {
	def temperature = evt.doubleValue
    //setpoint = temperature
	log.debug "Desired Temperature set to: $temperature $state.contact"
    
    def thisTemp = getAverageTemperature()
	if (state.contact) {
		evaluate(thisTemp, temperature)
	}
	else {
		coolingOff()
        log.debug("cooling off 3")
	}
}

def thermostatModeHandler(evt) {
	def mode = evt.value
	log.debug "Mode Changed to: $mode"
    def thermostat = getThermostat()
    
    def thisTemp = getAverageTemperature()
	if (state.contact) {
		evaluate(thisTemp, thermostat.currentValue("thermostatSetpoint"))
	}
	else {
		coolingOff(mode == 'cool' ? false : true)
        log.debug("cooling off 4 -mode-")
	}
}

private evaluate(currentTemp, desiredTemp)
{
	log.debug "EVALUATE($currentTemp, $desiredTemp)"
	// heater
	//if ( (desiredTemp - currentTemp >= threshold)) {
    if ( (desiredTemp - currentTemp <= threshold)) {
		coolingOn()
        log.debug("cooling on 1")
	}
	//if ( (currentTemp - desiredTemp >= threshold)) {
    else if ( (currentTemp - desiredTemp <= threshold)) {
		coolingOff()
        log.debug("cooling off 5")
	}
}

def coolingOn() {
    if(thermostat.currentValue('thermostatMode') == 'cool' || force) {
    	log.debug "Heating on Now"
        outlets.on()
        log.debug("Outlets on 1")
        thermostat.setCoolingStatus(true)
    } else {
        coolingOff(true)
        log.debug("cooling off 6")
    }
}

def coolingOff(coolingOff) {
	def thisTemp = getAverageTemperature()
    if (thisTemp >= emergencySetpoint) {
        log.debug "Heating in Emergency Mode Now"
        outlets.on()
        log.debug("Outlets on 2")
        thermostat.setEmergencyMode(true)
    } else {
    	log.debug "Heating off Now"
    	outlets.off()
        log.debug("outlets off 1")
		if(coolingOff) {
			thermostat.setCoolingOff(true)
		} else {
			thermostat.setCoolingStatus(false)
		}
    }
}