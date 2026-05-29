# TAKPacket-SDK Compression Report
Generated: 2026-05-29 | Dictionary: non-aircraft 512KB + aircraft 4KB (proto-trained, zstd-19)

## Summary
| Metric | Value |
|--------|-------|
| Total test messages | 47 |
| 100% under 237B | YES |
| Median compressed size | 87B |
| Median compression ratio | 7.2x |
| Worst case | 184B (77% of LoRa MTU) |

## Per-Message Results
| Fixture | CoT Type | XML Size | Proto Size | Compressed | Ratio | Dict |
|---------|----------|----------|------------|------------|-------|------|
| aircraft_adsb | a-n-A-C-F | 715B | 232B | 113B | 6.3x | aircraft |
| aircraft_hostile | a-h-A-M-F-F | 612B | 170B | 125B | 4.9x | aircraft |
| alert_tic | b-a-o-opn | 407B | 109B | 98B | 4.2x | non-aircraft |
| casevac | b-r-f-h-c | 594B | 133B | 134B | 4.4x | uncompressed |
| casevac_medline | b-r-f-h-c | 1045B | 177B | 160B | 6.5x | non-aircraft |
| chat_receipt_delivered | b-t-f-d | 479B | 109B | 77B | 6.2x | non-aircraft |
| chat_receipt_read | b-t-f-r | 479B | 109B | 77B | 6.2x | non-aircraft |
| chat_taktalk_dm | b-t-f | 1138B | 217B | 135B | 8.4x | non-aircraft |
| chat_taktalk_voice_profile | b-t-f | 1195B | 257B | 163B | 7.3x | non-aircraft |
| delete_event | t-x-d-d | 476B | 45B | 46B | 10.3x | uncompressed |
| drawing_circle | u-d-c-c | 933B | 114B | 72B | 13.0x | non-aircraft |
| drawing_circle_large | u-d-c-c | 626B | 109B | 99B | 6.3x | non-aircraft |
| drawing_ellipse | u-d-c-e | 646B | 77B | 62B | 10.4x | non-aircraft |
| drawing_freeform | u-d-f | 793B | 126B | 110B | 7.2x | non-aircraft |
| drawing_polygon | u-d-p | 790B | 126B | 118B | 6.7x | non-aircraft |
| drawing_rectangle | u-d-r | 770B | 127B | 97B | 7.9x | non-aircraft |
| drawing_rectangle_itak | u-d-r | 754B | 137B | 114B | 6.6x | non-aircraft |
| drawing_telestration | u-d-f-m | 2130B | 244B | 181B | 11.8x | non-aircraft |
| emergency_911 | b-a-o-tbl | 477B | 85B | 72B | 6.6x | non-aircraft |
| emergency_cancel | b-a-o-can | 538B | 100B | 79B | 6.8x | non-aircraft |
| geochat_broadcast | b-t-f | 896B | 101B | 64B | 14.0x | non-aircraft |
| geochat_dm | b-t-f | 960B | 141B | 67B | 14.3x | non-aircraft |
| geochat_simple | b-t-f | 836B | 128B | 73B | 11.5x | non-aircraft |
| marker_2525 | a-u-G | 714B | 163B | 68B | 10.5x | non-aircraft |
| marker_goto | b-m-p-w-GOTO | 560B | 98B | 66B | 8.5x | non-aircraft |
| marker_goto_itak | b-m-p-w-GOTO | 616B | 192B | 113B | 5.5x | non-aircraft |
| marker_icon_set | a-u-G | 736B | 185B | 70B | 10.5x | non-aircraft |
| marker_spot | b-m-p-s-m | 723B | 164B | 73B | 9.9x | non-aircraft |
| marker_tank | a-h-G-E-V-A-T | 700B | 137B | 75B | 9.3x | non-aircraft |
| pli_basic | a-f-G-U-C | 446B | 49B | 42B | 10.6x | non-aircraft |
| pli_full | a-f-G-U-C | 754B | 177B | 98B | 7.7x | non-aircraft |
| pli_itak | a-f-G-U-C | 533B | 80B | 79B | 6.7x | non-aircraft |
| pli_stationary | a-f-G-U-C | 619B | 136B | 109B | 5.7x | non-aircraft |
| pli_takaware | a-f-G-U-C | 539B | 97B | 87B | 6.2x | non-aircraft |
| pli_webtak | a-f-G-U-C-I | 668B | 125B | 79B | 8.5x | non-aircraft |
| pli_with_sensor | a-f-G-U-C | 772B | 162B | 118B | 6.5x | non-aircraft |
| ranging_bullseye | u-r-b-bullseye | 770B | 111B | 98B | 7.9x | non-aircraft |
| ranging_circle | u-r-b-c-c | 672B | 109B | 80B | 8.4x | non-aircraft |
| ranging_line | u-rb-a | 629B | 95B | 91B | 6.9x | non-aircraft |
| route_3wp | b-m-r | 861B | 185B | 122B | 7.1x | non-aircraft |
| route_itak_3wp | b-m-r | 771B | 233B | 184B | 4.2x | non-aircraft |
| taktalk_room_data | y- | 558B | 123B | 96B | 5.8x | non-aircraft |
| taktalk_text | m-t-t | 527B | 120B | 72B | 7.3x | non-aircraft |
| taktalk_voice | m-t-t | 526B | 108B | 80B | 6.6x | non-aircraft |
| taktalk_voice_marti | m-t-t | 577B | 142B | 113B | 5.1x | non-aircraft |
| task_engage | t-s | 531B | 109B | 66B | 8.0x | non-aircraft |
| waypoint | b-m-p-w | 574B | 124B | 55B | 10.4x | non-aircraft |

## Size Distribution
```
pli_basic              42B |########
delete_event           46B |#########
waypoint               55B |###########
drawing_ellipse        62B |#############
geochat_broadcast      64B |#############
marker_goto            66B |#############
task_engage            66B |#############
geochat_dm             67B |##############
marker_2525            68B |##############
marker_icon_set        70B |##############
drawing_circle         72B |###############
emergency_911          72B |###############
taktalk_text           72B |###############
geochat_simple         73B |###############
marker_spot            73B |###############
marker_tank            75B |###############
chat_receipt_delivered   77B |################
chat_receipt_read      77B |################
emergency_cancel       79B |################
pli_itak               79B |################
pli_webtak             79B |################
ranging_circle         80B |################
taktalk_voice          80B |################
pli_takaware           87B |##################
ranging_line           91B |###################
taktalk_room_data      96B |####################
drawing_rectangle      97B |####################
alert_tic              98B |####################
pli_full               98B |####################
ranging_bullseye       98B |####################
drawing_circle_large   99B |####################
pli_stationary        109B |######################
drawing_freeform      110B |#######################
aircraft_adsb         113B |#######################
marker_goto_itak      113B |#######################
taktalk_voice_marti   113B |#######################
drawing_rectangle_itak  114B |########################
drawing_polygon       118B |########################
pli_with_sensor       118B |########################
route_3wp             122B |#########################
aircraft_hostile      125B |##########################
casevac               134B |############################
chat_taktalk_dm       135B |############################
casevac_medline       160B |#################################
chat_taktalk_voice_profile  163B |##################################
drawing_telestration  181B |######################################
route_itak_3wp        184B |######################################
LoRa MTU              237B |##################################################
```
