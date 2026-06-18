# TAKPacket-SDK Compression Report
Generated: 2026-06-17 | Dictionary: non-aircraft 512KB + aircraft 4KB (proto-trained, zstd-19)

## Summary
| Metric | Value |
|--------|-------|
| Total test messages | 47 |
| 100% under 237B | YES |
| Median compressed size | 89B |
| Median compression ratio | 6.7x |
| Worst case | 220B (92% of LoRa MTU) |

## Per-Message Results
| Fixture | CoT Type | XML Size | Proto Size | Compressed | Ratio | Dict |
|---------|----------|----------|------------|------------|-------|------|
| aircraft_adsb | a-n-A-C-F | 715B | 232B | 125B | 5.7x | aircraft |
| aircraft_hostile | a-h-A-M-F-F | 612B | 170B | 128B | 4.8x | aircraft |
| alert_tic | b-a-o-opn | 407B | 109B | 101B | 4.0x | non-aircraft |
| casevac | b-r-f-h-c | 594B | 133B | 134B | 4.4x | uncompressed |
| casevac_medline | b-r-f-h-c | 1045B | 177B | 164B | 6.4x | non-aircraft |
| chat_receipt_delivered | b-t-f-d | 479B | 109B | 75B | 6.4x | non-aircraft |
| chat_receipt_read | b-t-f-r | 479B | 109B | 75B | 6.4x | non-aircraft |
| chat_taktalk_dm | b-t-f | 1138B | 217B | 148B | 7.7x | non-aircraft |
| chat_taktalk_voice_profile | b-t-f | 1195B | 257B | 188B | 6.4x | non-aircraft |
| delete_event | t-x-d-d | 476B | 45B | 46B | 10.3x | uncompressed |
| drawing_circle | u-d-c-c | 933B | 114B | 78B | 12.0x | non-aircraft |
| drawing_circle_large | u-d-c-c | 626B | 109B | 97B | 6.5x | non-aircraft |
| drawing_ellipse | u-d-c-e | 646B | 77B | 64B | 10.1x | non-aircraft |
| drawing_freeform | u-d-f | 793B | 126B | 115B | 6.9x | non-aircraft |
| drawing_polygon | u-d-p | 790B | 126B | 118B | 6.7x | non-aircraft |
| drawing_rectangle | u-d-r | 770B | 127B | 104B | 7.4x | non-aircraft |
| drawing_rectangle_itak | u-d-r | 754B | 137B | 122B | 6.2x | non-aircraft |
| drawing_telestration | u-d-f-m | 2130B | 244B | 193B | 11.0x | non-aircraft |
| emergency_911 | b-a-o-tbl | 477B | 85B | 73B | 6.5x | non-aircraft |
| emergency_cancel | b-a-o-can | 538B | 100B | 80B | 6.7x | non-aircraft |
| geochat_broadcast | b-t-f | 896B | 101B | 66B | 13.6x | non-aircraft |
| geochat_dm | b-t-f | 960B | 141B | 70B | 13.7x | non-aircraft |
| geochat_simple | b-t-f | 836B | 128B | 70B | 11.9x | non-aircraft |
| marker_2525 | a-u-G | 714B | 163B | 80B | 8.9x | non-aircraft |
| marker_goto | b-m-p-w-GOTO | 560B | 98B | 68B | 8.2x | non-aircraft |
| marker_goto_itak | b-m-p-w-GOTO | 616B | 192B | 118B | 5.2x | non-aircraft |
| marker_icon_set | a-u-G | 736B | 185B | 83B | 8.9x | non-aircraft |
| marker_spot | b-m-p-s-m | 723B | 164B | 78B | 9.3x | non-aircraft |
| marker_tank | a-h-G-E-V-A-T | 700B | 137B | 81B | 8.6x | non-aircraft |
| pli_basic | a-f-G-U-C | 446B | 49B | 42B | 10.6x | non-aircraft |
| pli_full | a-f-G-U-C | 754B | 177B | 98B | 7.7x | non-aircraft |
| pli_itak | a-f-G-U-C | 533B | 80B | 79B | 6.7x | non-aircraft |
| pli_stationary | a-f-G-U-C | 619B | 136B | 111B | 5.6x | non-aircraft |
| pli_takaware | a-f-G-U-C | 539B | 97B | 89B | 6.1x | non-aircraft |
| pli_webtak | a-f-G-U-C-I | 668B | 125B | 86B | 7.8x | non-aircraft |
| pli_with_sensor | a-f-G-U-C | 772B | 162B | 117B | 6.6x | non-aircraft |
| ranging_bullseye | u-r-b-bullseye | 770B | 111B | 102B | 7.5x | non-aircraft |
| ranging_circle | u-r-b-c-c | 672B | 109B | 88B | 7.6x | non-aircraft |
| ranging_line | u-rb-a | 629B | 95B | 93B | 6.8x | non-aircraft |
| route_3wp | b-m-r | 861B | 185B | 134B | 6.4x | non-aircraft |
| route_itak_3wp | b-m-r | 771B | 233B | 220B | 3.5x | non-aircraft |
| taktalk_room_data | y- | 558B | 123B | 109B | 5.1x | non-aircraft |
| taktalk_text | m-t-t | 527B | 120B | 82B | 6.4x | non-aircraft |
| taktalk_voice | m-t-t | 526B | 108B | 84B | 6.3x | non-aircraft |
| taktalk_voice_marti | m-t-t | 577B | 142B | 118B | 4.9x | non-aircraft |
| task_engage | t-s | 531B | 109B | 67B | 7.9x | non-aircraft |
| waypoint | b-m-p-w | 574B | 124B | 56B | 10.3x | non-aircraft |

## Size Distribution
```
pli_basic              42B |########
delete_event           46B |#########
waypoint               56B |###########
drawing_ellipse        64B |#############
geochat_broadcast      66B |#############
task_engage            67B |##############
marker_goto            68B |##############
geochat_dm             70B |##############
geochat_simple         70B |##############
emergency_911          73B |###############
chat_receipt_delivered   75B |###############
chat_receipt_read      75B |###############
drawing_circle         78B |################
marker_spot            78B |################
pli_itak               79B |################
emergency_cancel       80B |################
marker_2525            80B |################
marker_tank            81B |#################
taktalk_text           82B |#################
marker_icon_set        83B |#################
taktalk_voice          84B |#################
pli_webtak             86B |##################
ranging_circle         88B |##################
pli_takaware           89B |##################
ranging_line           93B |###################
drawing_circle_large   97B |####################
pli_full               98B |####################
alert_tic             101B |#####################
ranging_bullseye      102B |#####################
drawing_rectangle     104B |#####################
taktalk_room_data     109B |######################
pli_stationary        111B |#######################
drawing_freeform      115B |########################
pli_with_sensor       117B |########################
drawing_polygon       118B |########################
marker_goto_itak      118B |########################
taktalk_voice_marti   118B |########################
drawing_rectangle_itak  122B |#########################
aircraft_adsb         125B |##########################
aircraft_hostile      128B |###########################
casevac               134B |############################
route_3wp             134B |############################
chat_taktalk_dm       148B |###############################
casevac_medline       164B |##################################
chat_taktalk_voice_profile  188B |#######################################
drawing_telestration  193B |########################################
route_itak_3wp        220B |##############################################
LoRa MTU              237B |##################################################
```
