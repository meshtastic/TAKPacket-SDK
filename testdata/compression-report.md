# TAKPacket-SDK Compression Report
Generated: 2026-04-15 | Dictionary: v2 (non-aircraft 16KB + aircraft 4KB)

## Summary
| Metric | Value |
|--------|-------|
| Total test messages | 41 |
| 100% under 237B | YES |
| Median compressed size | 95B |
| Median compression ratio | 6.4x |
| Worst case | 212B (89% of LoRa MTU) |

## Per-Message Results
| Fixture | CoT Type | XML Size | Proto Size | Compressed | Ratio | Dict |
|---------|----------|----------|------------|------------|-------|------|
| aircraft_adsb | a-n-A-C-F | 716B | 232B | 197B | 3.6x | aircraft |
| aircraft_hostile | a-h-A-M-F-F | 613B | 170B | 144B | 4.3x | aircraft |
| alert_tic | b-a-o-opn | 408B | 109B | 114B | 3.6x | non-aircraft |
| casevac | b-r-f-h-c | 595B | 133B | 147B | 4.0x | non-aircraft |
| casevac_medline | b-r-f-h-c | 1046B | 177B | 191B | 5.5x | non-aircraft |
| chat_receipt_delivered | b-t-f-d | 479B | 109B | 85B | 5.6x | non-aircraft |
| chat_receipt_read | b-t-f-r | 479B | 109B | 85B | 5.6x | non-aircraft |
| delete_event | t-x-d-d | 476B | 48B | 62B | 7.7x | non-aircraft |
| drawing_circle | u-d-c-c | 930B | 120B | 95B | 9.8x | non-aircraft |
| drawing_circle_large | u-d-c-c | 625B | 109B | 80B | 7.8x | non-aircraft |
| drawing_ellipse | u-d-c-e | 643B | 77B | 75B | 8.6x | non-aircraft |
| drawing_freeform | u-d-f | 779B | 144B | 127B | 6.1x | non-aircraft |
| drawing_polygon | u-d-p | 778B | 140B | 122B | 6.4x | non-aircraft |
| drawing_rectangle | u-d-r | 755B | 137B | 103B | 7.3x | non-aircraft |
| drawing_rectangle_itak | u-d-r | 744B | 147B | 116B | 6.4x | non-aircraft |
| drawing_telestration | u-d-f-m | 2018B | 356B | 212B | 9.5x | non-aircraft |
| emergency_911 | b-a-o-tbl | 478B | 85B | 83B | 5.8x | non-aircraft |
| emergency_cancel | b-a-o-can | 539B | 100B | 91B | 5.9x | non-aircraft |
| geochat_broadcast | b-t-f | 897B | 101B | 56B | 16.0x | non-aircraft |
| geochat_dm | b-t-f | 961B | 141B | 72B | 13.3x | non-aircraft |
| geochat_simple | b-t-f | 836B | 128B | 78B | 10.7x | non-aircraft |
| marker_2525 | a-u-G | 712B | 163B | 107B | 6.7x | non-aircraft |
| marker_goto | b-m-p-w-GOTO | 558B | 98B | 66B | 8.5x | non-aircraft |
| marker_goto_itak | b-m-p-w-GOTO | 615B | 192B | 132B | 4.7x | non-aircraft |
| marker_icon_set | a-u-G | 734B | 185B | 130B | 5.6x | non-aircraft |
| marker_spot | b-m-p-s-m | 721B | 164B | 79B | 9.1x | non-aircraft |
| marker_tank | a-h-G-E-V-A-T | 698B | 137B | 92B | 7.6x | non-aircraft |
| pli_basic | a-f-G-U-C | 446B | 52B | 57B | 7.8x | non-aircraft |
| pli_full | a-f-G-U-C | 754B | 180B | 150B | 5.0x | non-aircraft |
| pli_itak | a-f-G-U-C | 534B | 83B | 79B | 6.8x | non-aircraft |
| pli_stationary | a-f-G-U-C | 620B | 139B | 114B | 5.4x | non-aircraft |
| pli_takaware | a-f-G-U-C | 540B | 100B | 86B | 6.3x | non-aircraft |
| pli_webtak | a-f-G-U-C-I | 668B | 128B | 131B | 5.1x | non-aircraft |
| pli_with_sensor | a-f-G-U-C | 774B | 165B | 152B | 5.1x | non-aircraft |
| ranging_bullseye | u-r-b-bullseye | 767B | 111B | 103B | 7.4x | non-aircraft |
| ranging_circle | u-r-b-c-c | 669B | 109B | 89B | 7.5x | non-aircraft |
| ranging_line | u-rb-a | 623B | 95B | 92B | 6.8x | non-aircraft |
| route_3wp | b-m-r | 849B | 185B | 117B | 7.3x | non-aircraft |
| route_itak_3wp | b-m-r | 767B | 233B | 170B | 4.5x | non-aircraft |
| task_engage | t-s | 532B | 109B | 91B | 5.8x | non-aircraft |
| waypoint | b-m-p-w | 571B | 124B | 83B | 6.9x | non-aircraft |

## Size Distribution
```
geochat_broadcast      56B |###########
pli_basic              57B |############
delete_event           62B |#############
marker_goto            66B |#############
geochat_dm             72B |###############
drawing_ellipse        75B |###############
geochat_simple         78B |################
marker_spot            79B |################
pli_itak               79B |################
drawing_circle_large   80B |################
emergency_911          83B |#################
waypoint               83B |#################
chat_receipt_delivered   85B |#################
chat_receipt_read      85B |#################
pli_takaware           86B |##################
ranging_circle         89B |##################
emergency_cancel       91B |###################
task_engage            91B |###################
marker_tank            92B |###################
ranging_line           92B |###################
drawing_circle         95B |####################
drawing_rectangle     103B |#####################
ranging_bullseye      103B |#####################
marker_2525           107B |######################
alert_tic             114B |########################
pli_stationary        114B |########################
drawing_rectangle_itak  116B |########################
route_3wp             117B |########################
drawing_polygon       122B |#########################
drawing_freeform      127B |##########################
marker_icon_set       130B |###########################
pli_webtak            131B |###########################
marker_goto_itak      132B |###########################
aircraft_hostile      144B |##############################
casevac               147B |###############################
pli_full              150B |###############################
pli_with_sensor       152B |################################
route_itak_3wp        170B |###################################
casevac_medline       191B |########################################
aircraft_adsb         197B |#########################################
drawing_telestration  212B |############################################
LoRa MTU              237B |##################################################
```
