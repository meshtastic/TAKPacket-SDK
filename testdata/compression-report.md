# TAKPacket-SDK Compression Report
Generated: 2026-11-23 | Dictionary: v1 (non-aircraft 8KB + aircraft 4KB)

## Summary
| Metric | Value |
|--------|-------|
| Total test messages | 47 |
| 100% under 225B | YES |
| Median compressed size | 88B |
| Median compression ratio | 7.4x |
| Worst case | 180B (80% of LoRa MTU) |

## Per-Message Results
| Fixture | CoT Type | XML Size | Proto Size | Compressed | Ratio | Dict |
|---------|----------|----------|------------|------------|-------|------|
| aircraft_adsb | a-n-A-C-F | 715B | 232B | 113B | 6.3x | aircraft |
| aircraft_hostile | a-h-A-M-F-F | 612B | 170B | 125B | 4.9x | aircraft |
| alert_tic | b-a-o-opn | 407B | 109B | 96B | 4.2x | non-aircraft |
| casevac | b-r-f-h-c | 594B | 133B | 132B | 4.5x | non-aircraft |
| casevac_medline | b-r-f-h-c | 1045B | 177B | 164B | 6.4x | non-aircraft |
| chat_receipt_delivered | b-t-f-d | 479B | 109B | 76B | 6.3x | non-aircraft |
| chat_receipt_read | b-t-f-r | 479B | 109B | 76B | 6.3x | non-aircraft |
| chat_taktalk_dm | b-t-f | 1138B | 217B | 129B | 8.8x | non-aircraft |
| chat_taktalk_voice_profile | b-t-f | 1195B | 257B | 155B | 7.7x | non-aircraft |
| delete_event | t-x-d-d | 476B | 45B | 46B | 10.3x | uncompressed |
| drawing_circle | u-d-c-c | 933B | 114B | 70B | 13.3x | non-aircraft |
| drawing_circle_large | u-d-c-c | 626B | 109B | 94B | 6.7x | non-aircraft |
| drawing_ellipse | u-d-c-e | 646B | 77B | 60B | 10.8x | non-aircraft |
| drawing_freeform | u-d-f | 793B | 126B | 111B | 7.1x | non-aircraft |
| drawing_polygon | u-d-p | 790B | 126B | 120B | 6.6x | non-aircraft |
| drawing_rectangle | u-d-r | 770B | 127B | 98B | 7.9x | non-aircraft |
| drawing_rectangle_itak | u-d-r | 754B | 137B | 118B | 6.4x | non-aircraft |
| drawing_telestration | u-d-f-m | 2130B | 244B | 179B | 11.9x | non-aircraft |
| emergency_911 | b-a-o-tbl | 477B | 85B | 71B | 6.7x | non-aircraft |
| emergency_cancel | b-a-o-can | 538B | 100B | 77B | 7.0x | non-aircraft |
| geochat_broadcast | b-t-f | 896B | 101B | 63B | 14.2x | non-aircraft |
| geochat_dm | b-t-f | 960B | 141B | 65B | 14.8x | non-aircraft |
| geochat_simple | b-t-f | 836B | 128B | 67B | 12.5x | non-aircraft |
| marker_2525 | a-u-G | 714B | 163B | 76B | 9.4x | non-aircraft |
| marker_goto | b-m-p-w-GOTO | 560B | 98B | 30B | 18.7x | non-aircraft |
| marker_goto_itak | b-m-p-w-GOTO | 616B | 192B | 133B | 4.6x | non-aircraft |
| marker_icon_set | a-u-G | 736B | 185B | 87B | 8.5x | non-aircraft |
| marker_spot | b-m-p-s-m | 723B | 164B | 67B | 10.8x | non-aircraft |
| marker_tank | a-h-G-E-V-A-T | 700B | 137B | 70B | 10.0x | non-aircraft |
| pli_basic | a-f-G-U-C | 446B | 49B | 43B | 10.4x | non-aircraft |
| pli_full | a-f-G-U-C | 754B | 177B | 97B | 7.8x | non-aircraft |
| pli_itak | a-f-G-U-C | 533B | 80B | 72B | 7.4x | non-aircraft |
| pli_stationary | a-f-G-U-C | 619B | 136B | 112B | 5.5x | non-aircraft |
| pli_takaware | a-f-G-U-C | 539B | 97B | 88B | 6.1x | non-aircraft |
| pli_webtak | a-f-G-U-C-I | 668B | 125B | 110B | 6.1x | non-aircraft |
| pli_with_sensor | a-f-G-U-C | 772B | 118B | 73B | 10.6x | non-aircraft |
| ranging_bullseye | u-r-b-bullseye | 770B | 111B | 95B | 8.1x | non-aircraft |
| ranging_circle | u-r-b-c-c | 672B | 109B | 80B | 8.4x | non-aircraft |
| ranging_line | u-rb-a | 629B | 95B | 92B | 6.8x | non-aircraft |
| route_3wp | b-m-r | 861B | 185B | 131B | 6.6x | non-aircraft |
| route_itak_3wp | b-m-r | 771B | 233B | 180B | 4.3x | non-aircraft |
| taktalk_room_data | y- | 558B | 123B | 93B | 6.0x | non-aircraft |
| taktalk_text | m-t-t | 527B | 120B | 68B | 7.8x | non-aircraft |
| taktalk_voice | m-t-t | 526B | 108B | 75B | 7.0x | non-aircraft |
| taktalk_voice_marti | m-t-t | 577B | 142B | 111B | 5.2x | non-aircraft |
| task_engage | t-s | 531B | 109B | 65B | 8.2x | non-aircraft |
| waypoint | b-m-p-w | 574B | 124B | 44B | 13.0x | non-aircraft |

## Size Distribution
```text
marker_goto            30B |######
pli_basic              43B |#########
waypoint               44B |#########
delete_event           46B |##########
drawing_ellipse        60B |#############
geochat_broadcast      63B |##############
geochat_dm             65B |##############
task_engage            65B |##############
geochat_simple         67B |##############
marker_spot            67B |##############
taktalk_text           68B |###############
drawing_circle         70B |###############
marker_tank            70B |###############
emergency_911          71B |###############
pli_itak               72B |################
pli_with_sensor        73B |################
taktalk_voice          75B |################
chat_receipt_delivered   76B |################
chat_receipt_read      76B |################
marker_2525            76B |################
emergency_cancel       77B |#################
ranging_circle         80B |#################
marker_icon_set        87B |###################
pli_takaware           88B |###################
ranging_line           92B |####################
taktalk_room_data      93B |####################
drawing_circle_large   94B |####################
ranging_bullseye       95B |#####################
alert_tic              96B |#####################
pli_full               97B |#####################
drawing_rectangle      98B |#####################
pli_webtak            110B |########################
drawing_freeform      111B |########################
taktalk_voice_marti   111B |########################
pli_stationary        112B |########################
aircraft_adsb         113B |#########################
drawing_rectangle_itak  118B |##########################
drawing_polygon       120B |##########################
aircraft_hostile      125B |###########################
chat_taktalk_dm       129B |############################
route_3wp             131B |#############################
casevac               132B |#############################
marker_goto_itak      133B |#############################
chat_taktalk_voice_profile  155B |##################################
casevac_medline       164B |####################################
drawing_telestration  179B |#######################################
route_itak_3wp        180B |########################################
LoRa MTU              225B |##################################################
```
