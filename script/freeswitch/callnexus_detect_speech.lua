local currentSession = session

if (currentSession == nil or not currentSession:ready()) and argv ~= nil and argv[1] ~= nil and argv[1] ~= "" then
    currentSession = freeswitch.Session(argv[1])
end

if currentSession == nil or not currentSession:ready() then
    freeswitch.consoleLog("WARNING", "CallNexus detect speech skipped: channel is not ready\n")
    return
end

local profile = currentSession:getVariable("callnexus_unimrcp_profile")
local grammar = currentSession:getVariable("callnexus_unimrcp_grammar")

if profile == nil or profile == "" then
    profile = "callnexus-mrcp-v2"
end

if grammar == nil or grammar == "" then
    grammar = "{start-input-timers=true,no-input-timeout=15000}builtin:speech/transcribe transcribe"
end

currentSession:execute("set", "fire_asr_events=true")
currentSession:execute("detect_speech", profile .. " " .. grammar)
