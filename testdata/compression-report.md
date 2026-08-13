# TAKPacket-SDK Compression Report
Generated: 2026-08-13 | Dictionary: non-aircraft 512KB + aircraft 4KB (proto-trained, zstd-19)

## Summary
| Metric | Value |
|--------|-------|
| Total test messages | 47 |
| 100% under 237B | YES |
| Median compressed size | 93B |
| Median compression ratio | 7.0x |
| Worst case | 211B (89% of LoRa MTU) |

## Per-Message Results
| Fixture | CoT Type | XML Size | Proto Size | Compressed | Ratio | Dict |
|---------|----------|----------|------------|------------|-------|------|
| aircraft_adsb | a-n-A-C-F | 715B | 232B | 125B | 5.7x | aircraft |
| aircraft_hostile | a-h-A-M-F-F | 612B | 170B | 128B | 4.8x | aircraft |
| alert_tic | b-a-o-opn | 407B | 109B | 98B | 4.2x | non-aircraft |
| casevac | b-r-f-h-c | 594B | 133B | 131B | 4.5x | non-aircraft |
| casevac_medline | b-r-f-h-c | 1045B | 177B | 163B | 6.4x | non-aircraft |
| chat_receipt_delivered | b-t-f-d | 479B | 109B | 76B | 6.3x | non-aircraft |
| chat_receipt_read | b-t-f-r | 479B | 109B | 76B | 6.3x | non-aircraft |
| chat_taktalk_dm | b-t-f | 1138B | 217B | 142B | 8.0x | non-aircraft |
| chat_taktalk_voice_profile | b-t-f | 1195B | 257B | 181B | 6.6x | non-aircraft |
| delete_event | t-x-d-d | 476B | 45B | 46B | 10.3x | uncompressed |
| drawing_circle | u-d-c-c | 933B | 114B | 75B | 12.4x | non-aircraft |
| drawing_circle_large | u-d-c-c | 626B | 109B | 93B | 6.7x | non-aircraft |
| drawing_ellipse | u-d-c-e | 646B | 77B | 61B | 10.6x | non-aircraft |
| drawing_freeform | u-d-f | 793B | 126B | 114B | 7.0x | non-aircraft |
| drawing_polygon | u-d-p | 790B | 126B | 118B | 6.7x | non-aircraft |
| drawing_rectangle | u-d-r | 770B | 127B | 100B | 7.7x | non-aircraft |
| drawing_rectangle_itak | u-d-r | 754B | 137B | 120B | 6.3x | non-aircraft |
| drawing_telestration | u-d-f-m | 2130B | 244B | 195B | 10.9x | non-aircraft |
| emergency_911 | b-a-o-tbl | 477B | 85B | 72B | 6.6x | non-aircraft |
| emergency_cancel | b-a-o-can | 538B | 100B | 78B | 6.9x | non-aircraft |
| geochat_broadcast | b-t-f | 896B | 101B | 63B | 14.2x | non-aircraft |
| geochat_dm | b-t-f | 960B | 141B | 65B | 14.8x | non-aircraft |
| geochat_simple | b-t-f | 836B | 128B | 67B | 12.5x | non-aircraft |
| marker_2525 | a-u-G | 714B | 163B | 79B | 9.0x | non-aircraft |
| marker_goto | b-m-p-w-GOTO | 560B | 98B | 39B | 14.4x | non-aircraft |
| marker_goto_itak | b-m-p-w-GOTO | 616B | 192B | 153B | 4.0x | non-aircraft |
| marker_icon_set | a-u-G | 736B | 185B | 94B | 7.8x | non-aircraft |
| marker_spot | b-m-p-s-m | 723B | 164B | 74B | 9.8x | non-aircraft |
| marker_tank | a-h-G-E-V-A-T | 700B | 137B | 76B | 9.2x | non-aircraft |
| pli_basic | a-f-G-U-C | 446B | 49B | 42B | 10.6x | non-aircraft |
| pli_full | a-f-G-U-C | 754B | 177B | 98B | 7.7x | non-aircraft |
| pli_itak | a-f-G-U-C | 533B | 80B | 74B | 7.2x | non-aircraft |
| pli_stationary | a-f-G-U-C | 619B | 136B | 113B | 5.5x | non-aircraft |
| pli_takaware | a-f-G-U-C | 539B | 97B | 90B | 6.0x | non-aircraft |
| pli_webtak | a-f-G-U-C-I | 668B | 125B | 118B | 5.7x | non-aircraft |
| pli_with_sensor | a-f-G-U-C | 772B | 162B | 116B | 6.7x | non-aircraft |
| ranging_bullseye | u-r-b-bullseye | 770B | 111B | 96B | 8.0x | non-aircraft |
| ranging_circle | u-r-b-c-c | 672B | 109B | 86B | 7.8x | non-aircraft |
| ranging_line | u-rb-a | 629B | 95B | 92B | 6.8x | non-aircraft |
| route_3wp | b-m-r | 861B | 185B | 132B | 6.5x | non-aircraft |
| route_itak_3wp | b-m-r | 771B | 233B | 211B | 3.7x | non-aircraft |
| taktalk_room_data | y- | 558B | 123B | 105B | 5.3x | non-aircraft |
| taktalk_text | m-t-t | 527B | 120B | 67B | 7.9x | non-aircraft |
| taktalk_voice | m-t-t | 526B | 108B | 74B | 7.1x | non-aircraft |
| taktalk_voice_marti | m-t-t | 577B | 142B | 114B | 5.1x | non-aircraft |
| task_engage | t-s | 531B | 109B | 67B | 7.9x | non-aircraft |
| waypoint | b-m-p-w | 574B | 124B | 52B | 11.0x | non-aircraft |

## Size Distribution
```
marker_goto            39B |########
pli_basic              42B |########
delete_event           46B |#########
waypoint               52B |##########
drawing_ellipse        61B |############
geochat_broadcast      63B |#############
geochat_dm             65B |#############
geochat_simple         67B |##############
taktalk_text           67B |##############
task_engage            67B |##############
emergency_911          72B |###############
marker_spot            74B |###############
pli_itak               74B |###############
taktalk_voice          74B |###############
drawing_circle         75B |###############
chat_receipt_delivered   76B |################
chat_receipt_read      76B |################
marker_tank            76B |################
emergency_cancel       78B |################
marker_2525            79B |################
ranging_circle         86B |##################
pli_takaware           90B |##################
ranging_line           92B |###################
drawing_circle_large   93B |###################
marker_icon_set        94B |###################
ranging_bullseye       96B |####################
alert_tic              98B |####################
pli_full               98B |####################
drawing_rectangle     100B |#####################
taktalk_room_data     105B |######################
pli_stationary        113B |#######################
drawing_freeform      114B |########################
taktalk_voice_marti   114B |########################
pli_with_sensor       116B |########################
drawing_polygon       118B |########################
pli_webtak            118B |########################
drawing_rectangle_itak  120B |#########################
aircraft_adsb         125B |##########################
aircraft_hostile      128B |###########################
casevac               131B |###########################
route_3wp             132B |###########################
chat_taktalk_dm       142B |#############################
marker_goto_itak      153B |################################
casevac_medline       163B |##################################
chat_taktalk_voice_profile  181B |######################################
drawing_telestration  195B |#########################################
route_itak_3wp        211B |############################################
LoRa MTU              237B |##################################################
```
