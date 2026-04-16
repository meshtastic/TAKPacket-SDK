# TAKPacket-SDK Compression Report
Generated: 2026-04-16 | Dictionary: v1 (non-aircraft 8KB + aircraft 4KB)

## Summary
| Metric | Value |
|--------|-------|
| Total test messages | 41 |
| 100% under 237B | YES |
| Median compressed size | 94B |
| Median compression ratio | 6.7x |
| Worst case | 212B (89% of LoRa MTU) |

## Per-Message Results
| Fixture | CoT Type | XML Size | Proto Size | Compressed | Ratio | Dict |
|---------|----------|----------|------------|------------|-------|------|
| aircraft_adsb | a-n-A-C-F | 715B | 232B | 197B | 3.6x | aircraft |
| aircraft_hostile | a-h-A-M-F-F | 612B | 170B | 144B | 4.2x | aircraft |
| alert_tic | b-a-o-opn | 407B | 109B | 114B | 3.6x | non-aircraft |
| casevac | b-r-f-h-c | 594B | 133B | 147B | 4.0x | non-aircraft |
| casevac_medline | b-r-f-h-c | 1045B | 177B | 191B | 5.5x | non-aircraft |
| chat_receipt_delivered | b-t-f-d | 479B | 109B | 85B | 5.6x | non-aircraft |
| chat_receipt_read | b-t-f-r | 479B | 109B | 85B | 5.6x | non-aircraft |
| delete_event | t-x-d-d | 476B | 48B | 62B | 7.7x | non-aircraft |
| drawing_circle | u-d-c-c | 933B | 114B | 92B | 10.1x | non-aircraft |
| drawing_circle_large | u-d-c-c | 626B | 109B | 80B | 7.8x | non-aircraft |
| drawing_ellipse | u-d-c-e | 646B | 77B | 75B | 8.6x | non-aircraft |
| drawing_freeform | u-d-f | 793B | 144B | 128B | 6.2x | non-aircraft |
| drawing_polygon | u-d-p | 790B | 140B | 123B | 6.4x | non-aircraft |
| drawing_rectangle | u-d-r | 770B | 137B | 104B | 7.4x | non-aircraft |
| drawing_rectangle_itak | u-d-r | 754B | 147B | 116B | 6.5x | non-aircraft |
| drawing_telestration | u-d-f-m | 2130B | 356B | 212B | 10.0x | non-aircraft |
| emergency_911 | b-a-o-tbl | 477B | 85B | 83B | 5.7x | non-aircraft |
| emergency_cancel | b-a-o-can | 538B | 100B | 91B | 5.9x | non-aircraft |
| geochat_broadcast | b-t-f | 896B | 101B | 58B | 15.4x | non-aircraft |
| geochat_dm | b-t-f | 960B | 141B | 72B | 13.3x | non-aircraft |
| geochat_simple | b-t-f | 836B | 128B | 78B | 10.7x | non-aircraft |
| marker_2525 | a-u-G | 714B | 163B | 107B | 6.7x | non-aircraft |
| marker_goto | b-m-p-w-GOTO | 560B | 98B | 66B | 8.5x | non-aircraft |
| marker_goto_itak | b-m-p-w-GOTO | 616B | 192B | 133B | 4.6x | non-aircraft |
| marker_icon_set | a-u-G | 736B | 185B | 131B | 5.6x | non-aircraft |
| marker_spot | b-m-p-s-m | 723B | 164B | 79B | 9.2x | non-aircraft |
| marker_tank | a-h-G-E-V-A-T | 700B | 137B | 94B | 7.4x | non-aircraft |
| pli_basic | a-f-G-U-C | 446B | 52B | 57B | 7.8x | non-aircraft |
| pli_full | a-f-G-U-C | 754B | 180B | 150B | 5.0x | non-aircraft |
| pli_itak | a-f-G-U-C | 533B | 83B | 78B | 6.8x | non-aircraft |
| pli_stationary | a-f-G-U-C | 619B | 139B | 114B | 5.4x | non-aircraft |
| pli_takaware | a-f-G-U-C | 539B | 100B | 86B | 6.3x | non-aircraft |
| pli_webtak | a-f-G-U-C-I | 668B | 128B | 132B | 5.1x | non-aircraft |
| pli_with_sensor | a-f-G-U-C | 772B | 121B | 108B | 7.1x | non-aircraft |
| ranging_bullseye | u-r-b-bullseye | 770B | 111B | 103B | 7.5x | non-aircraft |
| ranging_circle | u-r-b-c-c | 672B | 109B | 91B | 7.4x | non-aircraft |
| ranging_line | u-rb-a | 629B | 95B | 92B | 6.8x | non-aircraft |
| route_3wp | b-m-r | 861B | 185B | 117B | 7.4x | non-aircraft |
| route_itak_3wp | b-m-r | 771B | 233B | 171B | 4.5x | non-aircraft |
| task_engage | t-s | 531B | 109B | 91B | 5.8x | non-aircraft |
| waypoint | b-m-p-w | 574B | 124B | 83B | 6.9x | non-aircraft |

## Size Distribution
```
pli_basic              57B |############
geochat_broadcast      58B |############
delete_event           62B |#############
marker_goto            66B |#############
geochat_dm             72B |###############
drawing_ellipse        75B |###############
geochat_simple         78B |################
pli_itak               78B |################
marker_spot            79B |################
drawing_circle_large   80B |################
emergency_911          83B |#################
waypoint               83B |#################
chat_receipt_delivered   85B |#################
chat_receipt_read      85B |#################
pli_takaware           86B |##################
emergency_cancel       91B |###################
ranging_circle         91B |###################
task_engage            91B |###################
drawing_circle         92B |###################
ranging_line           92B |###################
marker_tank            94B |###################
ranging_bullseye      103B |#####################
drawing_rectangle     104B |#####################
marker_2525           107B |######################
pli_with_sensor       108B |######################
alert_tic             114B |########################
pli_stationary        114B |########################
drawing_rectangle_itak  116B |########################
route_3wp             117B |########################
drawing_polygon       123B |#########################
drawing_freeform      128B |###########################
marker_icon_set       131B |###########################
pli_webtak            132B |###########################
marker_goto_itak      133B |############################
aircraft_hostile      144B |##############################
casevac               147B |###############################
pli_full              150B |###############################
route_itak_3wp        171B |####################################
casevac_medline       191B |########################################
aircraft_adsb         197B |#########################################
drawing_telestration  212B |############################################
LoRa MTU              237B |##################################################
```
