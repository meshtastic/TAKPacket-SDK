# TAKPacket-SDK Compression Report
Generated: 2026-05-27 | Dictionary: v1 (non-aircraft 8KB + aircraft 4KB)

## Summary
| Metric | Value |
|--------|-------|
| Total test messages | 47 |
| 100% under 237B | YES |
| Median compressed size | 127B |
| Median compression ratio | 5.3x |
| Worst case | 216B (91% of LoRa MTU) |

## Per-Message Results
| Fixture | CoT Type | XML Size | Proto Size | Compressed | Ratio | Dict |
|---------|----------|----------|------------|------------|-------|------|
| aircraft_adsb | a-n-A-C-F | 715B | 232B | 147B | 4.9x | aircraft |
| aircraft_hostile | a-h-A-M-F-F | 612B | 170B | 138B | 4.4x | aircraft |
| alert_tic | b-a-o-opn | 407B | 109B | 118B | 3.4x | non-aircraft |
| casevac | b-r-f-h-c | 594B | 133B | 147B | 4.0x | non-aircraft |
| casevac_medline | b-r-f-h-c | 1045B | 177B | 191B | 5.5x | non-aircraft |
| chat_receipt_delivered | b-t-f-d | 479B | 109B | 110B | 4.4x | non-aircraft |
| chat_receipt_read | b-t-f-r | 479B | 109B | 110B | 4.4x | non-aircraft |
| chat_taktalk_dm | b-t-f | 1138B | 217B | 158B | 7.2x | non-aircraft |
| chat_taktalk_voice_profile | b-t-f | 1195B | 257B | 188B | 6.4x | non-aircraft |
| delete_event | t-x-d-d | 476B | 48B | 62B | 7.7x | non-aircraft |
| drawing_circle | u-d-c-c | 933B | 114B | 128B | 7.3x | non-aircraft |
| drawing_circle_large | u-d-c-c | 626B | 109B | 118B | 5.3x | non-aircraft |
| drawing_ellipse | u-d-c-e | 646B | 77B | 87B | 7.4x | non-aircraft |
| drawing_freeform | u-d-f | 793B | 144B | 158B | 5.0x | non-aircraft |
| drawing_polygon | u-d-p | 790B | 140B | 154B | 5.1x | non-aircraft |
| drawing_rectangle | u-d-r | 770B | 137B | 151B | 5.1x | non-aircraft |
| drawing_rectangle_itak | u-d-r | 754B | 147B | 143B | 5.3x | non-aircraft |
| drawing_telestration | u-d-f-m | 2130B | 356B | 216B | 9.9x | non-aircraft |
| emergency_911 | b-a-o-tbl | 477B | 85B | 88B | 5.4x | non-aircraft |
| emergency_cancel | b-a-o-can | 538B | 100B | 95B | 5.7x | non-aircraft |
| geochat_broadcast | b-t-f | 896B | 101B | 102B | 8.8x | non-aircraft |
| geochat_dm | b-t-f | 960B | 141B | 99B | 9.7x | non-aircraft |
| geochat_simple | b-t-f | 836B | 128B | 127B | 6.6x | non-aircraft |
| marker_2525 | a-u-G | 714B | 163B | 149B | 4.8x | non-aircraft |
| marker_goto | b-m-p-w-GOTO | 560B | 98B | 95B | 5.9x | non-aircraft |
| marker_goto_itak | b-m-p-w-GOTO | 616B | 192B | 171B | 3.6x | non-aircraft |
| marker_icon_set | a-u-G | 736B | 185B | 132B | 5.6x | non-aircraft |
| marker_spot | b-m-p-s-m | 723B | 164B | 154B | 4.7x | non-aircraft |
| marker_tank | a-h-G-E-V-A-T | 700B | 137B | 130B | 5.4x | non-aircraft |
| pli_basic | a-f-G-U-C | 446B | 52B | 58B | 7.7x | non-aircraft |
| pli_full | a-f-G-U-C | 754B | 180B | 138B | 5.5x | non-aircraft |
| pli_itak | a-f-G-U-C | 533B | 83B | 97B | 5.5x | non-aircraft |
| pli_stationary | a-f-G-U-C | 619B | 139B | 136B | 4.6x | non-aircraft |
| pli_takaware | a-f-G-U-C | 539B | 100B | 107B | 5.0x | non-aircraft |
| pli_webtak | a-f-G-U-C-I | 668B | 128B | 129B | 5.2x | non-aircraft |
| pli_with_sensor | a-f-G-U-C | 772B | 121B | 107B | 7.2x | non-aircraft |
| ranging_bullseye | u-r-b-bullseye | 770B | 111B | 125B | 6.2x | non-aircraft |
| ranging_circle | u-r-b-c-c | 672B | 109B | 123B | 5.5x | non-aircraft |
| ranging_line | u-rb-a | 629B | 95B | 109B | 5.8x | non-aircraft |
| route_3wp | b-m-r | 861B | 185B | 167B | 5.2x | non-aircraft |
| route_itak_3wp | b-m-r | 771B | 233B | 216B | 3.6x | non-aircraft |
| taktalk_room_data | y- | 558B | 123B | 120B | 4.7x | non-aircraft |
| taktalk_text | m-t-t | 527B | 120B | 127B | 4.1x | non-aircraft |
| taktalk_voice | m-t-t | 526B | 108B | 122B | 4.3x | non-aircraft |
| taktalk_voice_marti | m-t-t | 577B | 142B | 156B | 3.7x | non-aircraft |
| task_engage | t-s | 531B | 109B | 107B | 5.0x | non-aircraft |
| waypoint | b-m-p-w | 574B | 124B | 117B | 4.9x | non-aircraft |

## Size Distribution
```
pli_basic              58B |############
delete_event           62B |#############
drawing_ellipse        87B |##################
emergency_911          88B |##################
emergency_cancel       95B |####################
marker_goto            95B |####################
pli_itak               97B |####################
geochat_dm             99B |####################
geochat_broadcast     102B |#####################
pli_takaware          107B |######################
pli_with_sensor       107B |######################
task_engage           107B |######################
ranging_line          109B |######################
chat_receipt_delivered  110B |#######################
chat_receipt_read     110B |#######################
waypoint              117B |########################
alert_tic             118B |########################
drawing_circle_large  118B |########################
taktalk_room_data     120B |#########################
taktalk_voice         122B |#########################
ranging_circle        123B |#########################
ranging_bullseye      125B |##########################
geochat_simple        127B |##########################
taktalk_text          127B |##########################
drawing_circle        128B |###########################
pli_webtak            129B |###########################
marker_tank           130B |###########################
marker_icon_set       132B |###########################
pli_stationary        136B |############################
aircraft_hostile      138B |#############################
pli_full              138B |#############################
drawing_rectangle_itak  143B |##############################
aircraft_adsb         147B |###############################
casevac               147B |###############################
marker_2525           149B |###############################
drawing_rectangle     151B |###############################
drawing_polygon       154B |################################
marker_spot           154B |################################
taktalk_voice_marti   156B |################################
chat_taktalk_dm       158B |#################################
drawing_freeform      158B |#################################
route_3wp             167B |###################################
marker_goto_itak      171B |####################################
chat_taktalk_voice_profile  188B |#######################################
casevac_medline       191B |########################################
drawing_telestration  216B |#############################################
route_itak_3wp        216B |#############################################
LoRa MTU              237B |##################################################
```
