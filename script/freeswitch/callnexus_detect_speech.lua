local profile = session:getVariable("callnexus_unimrcp_profile")
local grammar = session:getVariable("callnexus_unimrcp_grammar")

if profile == nil or profile == "" then
    profile = "unimrcp"
end

if grammar == nil or grammar == "" then
    grammar = "{start-input-timers=true,no-input-timeout=15000}builtin:speech/transcribe transcribe"
end

session:execute("set", "fire_asr_events=true")
session:execute("detect_speech", profile .. " " .. grammar)
