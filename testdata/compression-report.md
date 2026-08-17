# TAKPacket-SDK Compression Report
Generated: 2026-08-17 | Dictionary: non-aircraft 512KB + aircraft 4KB (proto-trained, zstd-19)

## Summary
| Metric | Value |
|--------|-------|
| Total test messages | 47 |
| 100% under 237B | YES |
| Median compressed size | 89B |
| Median compression ratio | 7.3x |
| Worst case | 193B (81% of LoRa MTU) |

## Per-Message Results
| Fixture | CoT Type | XML Size | Proto Size | Compressed | Ratio | Dict |
|---------|----------|----------|------------|------------|-------|------|
| aircraft_adsb | a-n-A-C-F | 715B | 232B | 112B | 6.4x | aircraft |
| aircraft_hostile | a-h-A-M-F-F | 612B | 170B | 123B | 5.0x | aircraft |
| alert_tic | b-a-o-opn | 407B | 109B | 97B | 4.2x | non-aircraft |
| casevac | b-r-f-h-c | 594B | 133B | 130B | 4.6x | non-aircraft |
| casevac_medline | b-r-f-h-c | 1045B | 177B | 163B | 6.4x | non-aircraft |
| chat_receipt_delivered | b-t-f-d | 479B | 109B | 75B | 6.4x | non-aircraft |
| chat_receipt_read | b-t-f-r | 479B | 109B | 75B | 6.4x | non-aircraft |
| chat_taktalk_dm | b-t-f | 1138B | 217B | 137B | 8.3x | non-aircraft |
| chat_taktalk_voice_profile | b-t-f | 1195B | 257B | 165B | 7.2x | non-aircraft |
| delete_event | t-x-d-d | 476B | 45B | 45B | 10.6x | non-aircraft |
| drawing_circle | u-d-c-c | 933B | 114B | 71B | 13.1x | non-aircraft |
| drawing_circle_large | u-d-c-c | 626B | 109B | 90B | 7.0x | non-aircraft |
| drawing_ellipse | u-d-c-e | 646B | 77B | 60B | 10.8x | non-aircraft |
| drawing_freeform | u-d-f | 793B | 126B | 109B | 7.3x | non-aircraft |
| drawing_polygon | u-d-p | 790B | 126B | 115B | 6.9x | non-aircraft |
| drawing_rectangle | u-d-r | 770B | 127B | 97B | 7.9x | non-aircraft |
| drawing_rectangle_itak | u-d-r | 754B | 137B | 117B | 6.4x | non-aircraft |
| drawing_telestration | u-d-f-m | 2130B | 244B | 193B | 11.0x | non-aircraft |
| emergency_911 | b-a-o-tbl | 477B | 85B | 72B | 6.6x | non-aircraft |
| emergency_cancel | b-a-o-can | 538B | 100B | 78B | 6.9x | non-aircraft |
| geochat_broadcast | b-t-f | 896B | 101B | 61B | 14.7x | non-aircraft |
| geochat_dm | b-t-f | 960B | 141B | 64B | 15.0x | non-aircraft |
| geochat_simple | b-t-f | 836B | 128B | 65B | 12.9x | non-aircraft |
| marker_2525 | a-u-G | 714B | 163B | 71B | 10.1x | non-aircraft |
| marker_goto | b-m-p-w-GOTO | 560B | 98B | 33B | 17.0x | non-aircraft |
| marker_goto_itak | b-m-p-w-GOTO | 616B | 192B | 142B | 4.3x | non-aircraft |
| marker_icon_set | a-u-G | 736B | 185B | 89B | 8.3x | non-aircraft |
| marker_spot | b-m-p-s-m | 723B | 164B | 70B | 10.3x | non-aircraft |
| marker_tank | a-h-G-E-V-A-T | 700B | 137B | 75B | 9.3x | non-aircraft |
| pli_basic | a-f-G-U-C | 446B | 49B | 42B | 10.6x | non-aircraft |
| pli_full | a-f-G-U-C | 754B | 177B | 95B | 7.9x | non-aircraft |
| pli_itak | a-f-G-U-C | 533B | 80B | 71B | 7.5x | non-aircraft |
| pli_stationary | a-f-G-U-C | 619B | 136B | 111B | 5.6x | non-aircraft |
| pli_takaware | a-f-G-U-C | 539B | 97B | 88B | 6.1x | non-aircraft |
| pli_webtak | a-f-G-U-C-I | 668B | 125B | 112B | 6.0x | non-aircraft |
| pli_with_sensor | a-f-G-U-C | 772B | 162B | 115B | 6.7x | non-aircraft |
| ranging_bullseye | u-r-b-bullseye | 770B | 111B | 93B | 8.3x | non-aircraft |
| ranging_circle | u-r-b-c-c | 672B | 109B | 80B | 8.4x | non-aircraft |
| ranging_line | u-rb-a | 629B | 95B | 88B | 7.1x | non-aircraft |
| route_3wp | b-m-r | 861B | 185B | 122B | 7.1x | non-aircraft |
| route_itak_3wp | b-m-r | 771B | 233B | 181B | 4.3x | non-aircraft |
| taktalk_room_data | y- | 558B | 123B | 94B | 5.9x | non-aircraft |
| taktalk_text | m-t-t | 527B | 120B | 64B | 8.2x | non-aircraft |
| taktalk_voice | m-t-t | 526B | 108B | 70B | 7.5x | non-aircraft |
| taktalk_voice_marti | m-t-t | 577B | 142B | 110B | 5.2x | non-aircraft |
| task_engage | t-s | 531B | 109B | 67B | 7.9x | non-aircraft |
| waypoint | b-m-p-w | 574B | 124B | 50B | 11.5x | non-aircraft |

## Size Distribution
```text
marker_goto            33B |######
pli_basic              42B |########
delete_event           45B |#########
waypoint               50B |##########
drawing_ellipse        60B |############
geochat_broadcast      61B |############
geochat_dm             64B |#############
taktalk_text           64B |#############
geochat_simple         65B |#############
task_engage            67B |##############
marker_spot            70B |##############
taktalk_voice          70B |##############
drawing_circle         71B |##############
marker_2525            71B |##############
pli_itak               71B |##############
emergency_911          72B |###############
chat_receipt_delivered   75B |###############
chat_receipt_read      75B |###############
marker_tank            75B |###############
emergency_cancel       78B |################
ranging_circle         80B |################
pli_takaware           88B |##################
ranging_line           88B |##################
marker_icon_set        89B |##################
drawing_circle_large   90B |##################
ranging_bullseye       93B |###################
taktalk_room_data      94B |###################
pli_full               95B |####################
alert_tic              97B |####################
drawing_rectangle      97B |####################
drawing_freeform      109B |######################
taktalk_voice_marti   110B |#######################
pli_stationary        111B |#######################
aircraft_adsb         112B |#######################
pli_webtak            112B |#######################
drawing_polygon       115B |########################
pli_with_sensor       115B |########################
drawing_rectangle_itak  117B |########################
route_3wp             122B |#########################
aircraft_hostile      123B |#########################
casevac               130B |###########################
chat_taktalk_dm       137B |############################
marker_goto_itak      142B |#############################
casevac_medline       163B |##################################
chat_taktalk_voice_profile  165B |##################################
route_itak_3wp        181B |######################################
drawing_telestration  193B |########################################
LoRa MTU              237B |##################################################
```
