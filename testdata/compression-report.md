# TAKPacket-SDK Compression Report
Generated: 2026-04-09 | Dictionary: v2 (non-aircraft 16KB + aircraft 4KB)

## Summary
| Metric | Value |
|--------|-------|
| Total test messages | 32 |
| 100% under 237B | YES |
| Median compressed size | 115B |
| Median compression ratio | 5.4x |
| Worst case | 216B (91% of LoRa MTU) |

## Per-Message Results
| Fixture | CoT Type | XML Size | Proto Size | Compressed | Ratio | Dict |
|---------|----------|----------|------------|------------|-------|------|
| aircraft_adsb | a-n-A-C-F | 716B | 120B | 110B | 6.5x | aircraft |
| aircraft_hostile | a-h-A-M-F-F | 613B | 108B | 113B | 5.4x | aircraft |
| alert_tic | b-a-o-opn | 408B | 49B | 63B | 6.5x | non-aircraft |
| casevac | b-r-f-h-c | 595B | 90B | 86B | 6.9x | non-aircraft |
| casevac_medline | b-r-f-h-c | 808B | 85B | 99B | 8.2x | non-aircraft |
| chat_receipt_delivered | b-t-f-d | 479B | 113B | 113B | 4.2x | non-aircraft |
| chat_receipt_read | b-t-f-r | 479B | 113B | 113B | 4.2x | non-aircraft |
| delete_event | t-x-d-d | 476B | 99B | 91B | 5.2x | non-aircraft |
| drawing_circle | u-d-c-c | 930B | 114B | 128B | 7.3x | non-aircraft |
| drawing_ellipse | u-d-c-e | 643B | 77B | 91B | 7.1x | non-aircraft |
| drawing_freeform | u-d-f | 779B | 144B | 158B | 4.9x | non-aircraft |
| drawing_polygon | u-d-p | 778B | 140B | 154B | 5.1x | non-aircraft |
| drawing_rectangle | u-d-r | 755B | 137B | 151B | 5.0x | non-aircraft |
| drawing_telestration | u-d-f-m | 2018B | 356B | 216B | 9.3x | non-aircraft |
| emergency_911 | b-a-o-tbl | 478B | 85B | 87B | 5.5x | non-aircraft |
| emergency_cancel | b-a-o-can | 539B | 86B | 87B | 6.2x | non-aircraft |
| geochat_simple | b-t-f | 836B | 144B | 128B | 6.5x | non-aircraft |
| marker_2525 | a-u-G | 712B | 163B | 135B | 5.3x | non-aircraft |
| marker_goto | b-m-p-w-GOTO | 558B | 98B | 93B | 6.0x | non-aircraft |
| marker_icon_set | a-u-G | 734B | 185B | 159B | 4.6x | non-aircraft |
| marker_spot | b-m-p-s-m | 721B | 164B | 140B | 5.2x | non-aircraft |
| marker_tank | a-h-G-E-V-A-T | 698B | 137B | 111B | 6.3x | non-aircraft |
| pli_basic | a-f-G-U-C | 446B | 64B | 62B | 7.2x | non-aircraft |
| pli_full | a-f-G-U-C | 754B | 192B | 140B | 5.4x | non-aircraft |
| pli_stationary | a-f-G-U-C | 620B | 151B | 140B | 4.4x | non-aircraft |
| pli_webtak | a-f-G-U-C-I | 668B | 191B | 152B | 4.4x | non-aircraft |
| ranging_bullseye | u-r-b-bullseye | 767B | 111B | 125B | 6.1x | non-aircraft |
| ranging_circle | u-r-b-c-c | 669B | 109B | 123B | 5.4x | non-aircraft |
| ranging_line | u-rb-a | 623B | 95B | 109B | 5.7x | non-aircraft |
| route_3wp | b-m-r | 849B | 185B | 167B | 5.1x | non-aircraft |
| task_engage | t-s | 532B | 109B | 106B | 5.0x | non-aircraft |
| waypoint | b-m-p-w | 571B | 124B | 115B | 5.0x | non-aircraft |

## Size Distribution
```
pli_basic              62B |#############
alert_tic              63B |#############
casevac                86B |##################
emergency_911          87B |##################
emergency_cancel       87B |##################
delete_event           91B |###################
drawing_ellipse        91B |###################
marker_goto            93B |###################
casevac_medline        99B |####################
task_engage           106B |######################
ranging_line          109B |######################
aircraft_adsb         110B |#######################
marker_tank           111B |#######################
aircraft_hostile      113B |#######################
chat_receipt_delivered  113B |#######################
chat_receipt_read     113B |#######################
waypoint              115B |########################
ranging_circle        123B |#########################
ranging_bullseye      125B |##########################
drawing_circle        128B |###########################
geochat_simple        128B |###########################
marker_2525           135B |############################
marker_spot           140B |#############################
pli_full              140B |#############################
pli_stationary        140B |#############################
drawing_rectangle     151B |###############################
pli_webtak            152B |################################
drawing_polygon       154B |################################
drawing_freeform      158B |#################################
marker_icon_set       159B |#################################
route_3wp             167B |###################################
drawing_telestration  216B |#############################################
LoRa MTU              237B |##################################################
```
