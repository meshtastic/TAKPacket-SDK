# TAKPacket-SDK Compression Report
Generated: 2026-04-11 | Dictionary: v1 (non-aircraft 8KB + aircraft 4KB)

## Summary
| Metric | Value |
|--------|-------|
| Total test messages | 40 |
| 100% under 237B | YES |
| Median compressed size | 93B |
| Median compression ratio | 6.6x |
| Worst case | 210B (88% of LoRa MTU) |

## Per-Message Results
| Fixture | CoT Type | XML Size | Proto Size | Compressed | Ratio | Dict |
|---------|----------|----------|------------|------------|-------|------|
| aircraft_adsb | a-n-A-C-F | 716B | 120B | 109B | 6.6x | aircraft |
| aircraft_hostile | a-h-A-M-F-F | 613B | 108B | 114B | 5.4x | aircraft |
| alert_tic | b-a-o-opn | 408B | 49B | 63B | 6.5x | non-aircraft |
| casevac | b-r-f-h-c | 595B | 90B | 79B | 7.5x | non-aircraft |
| casevac_medline | b-r-f-h-c | 808B | 85B | 99B | 8.2x | non-aircraft |
| chat_receipt_delivered | b-t-f-d | 479B | 109B | 86B | 5.6x | non-aircraft |
| chat_receipt_read | b-t-f-r | 479B | 109B | 86B | 5.6x | non-aircraft |
| delete_event | t-x-d-d | 476B | 99B | 84B | 5.7x | non-aircraft |
| drawing_circle | u-d-c-c | 930B | 114B | 90B | 10.3x | non-aircraft |
| drawing_circle_large | u-d-c-c | 625B | 109B | 81B | 7.7x | non-aircraft |
| drawing_ellipse | u-d-c-e | 643B | 77B | 71B | 9.1x | non-aircraft |
| drawing_freeform | u-d-f | 779B | 144B | 126B | 6.2x | non-aircraft |
| drawing_polygon | u-d-p | 778B | 140B | 121B | 6.4x | non-aircraft |
| drawing_rectangle | u-d-r | 755B | 137B | 101B | 7.5x | non-aircraft |
| drawing_rectangle_itak | u-d-r | 744B | 147B | 118B | 6.3x | non-aircraft |
| drawing_telestration | u-d-f-m | 2018B | 356B | 210B | 9.6x | non-aircraft |
| emergency_911 | b-a-o-tbl | 478B | 85B | 83B | 5.8x | non-aircraft |
| emergency_cancel | b-a-o-can | 539B | 100B | 89B | 6.1x | non-aircraft |
| geochat_broadcast | b-t-f | 897B | 101B | 59B | 15.2x | non-aircraft |
| geochat_dm | b-t-f | 961B | 141B | 74B | 13.0x | non-aircraft |
| geochat_simple | b-t-f | 836B | 128B | 80B | 10.4x | non-aircraft |
| marker_2525 | a-u-G | 712B | 163B | 105B | 6.8x | non-aircraft |
| marker_goto | b-m-p-w-GOTO | 558B | 98B | 65B | 8.6x | non-aircraft |
| marker_goto_itak | b-m-p-w-GOTO | 615B | 192B | 130B | 4.7x | non-aircraft |
| marker_icon_set | a-u-G | 734B | 185B | 125B | 5.9x | non-aircraft |
| marker_spot | b-m-p-s-m | 721B | 164B | 81B | 8.9x | non-aircraft |
| marker_tank | a-h-G-E-V-A-T | 698B | 137B | 92B | 7.6x | non-aircraft |
| pli_basic | a-f-G-U-C | 446B | 64B | 69B | 6.5x | non-aircraft |
| pli_full | a-f-G-U-C | 754B | 192B | 164B | 4.6x | non-aircraft |
| pli_itak | a-f-G-U-C | 534B | 95B | 93B | 5.7x | non-aircraft |
| pli_stationary | a-f-G-U-C | 620B | 151B | 127B | 4.9x | non-aircraft |
| pli_takaware | a-f-G-U-C | 540B | 112B | 100B | 5.4x | non-aircraft |
| pli_webtak | a-f-G-U-C-I | 668B | 191B | 155B | 4.3x | non-aircraft |
| ranging_bullseye | u-r-b-bullseye | 767B | 111B | 103B | 7.4x | non-aircraft |
| ranging_circle | u-r-b-c-c | 669B | 109B | 88B | 7.6x | non-aircraft |
| ranging_line | u-rb-a | 623B | 95B | 92B | 6.8x | non-aircraft |
| route_3wp | b-m-r | 849B | 185B | 116B | 7.3x | non-aircraft |
| route_itak_3wp | b-m-r | 767B | 233B | 171B | 4.5x | non-aircraft |
| task_engage | t-s | 532B | 109B | 94B | 5.7x | non-aircraft |
| waypoint | b-m-p-w | 571B | 124B | 75B | 7.6x | non-aircraft |

## Size Distribution
```
geochat_broadcast      59B |############
alert_tic              63B |#############
marker_goto            65B |#############
pli_basic              69B |##############
drawing_ellipse        71B |##############
geochat_dm             74B |###############
waypoint               75B |###############
casevac                79B |################
geochat_simple         80B |################
drawing_circle_large   81B |#################
marker_spot            81B |#################
emergency_911          83B |#################
delete_event           84B |#################
chat_receipt_delivered   86B |##################
chat_receipt_read      86B |##################
ranging_circle         88B |##################
emergency_cancel       89B |##################
drawing_circle         90B |##################
marker_tank            92B |###################
ranging_line           92B |###################
pli_itak               93B |###################
task_engage            94B |###################
casevac_medline        99B |####################
pli_takaware          100B |#####################
drawing_rectangle     101B |#####################
ranging_bullseye      103B |#####################
marker_2525           105B |######################
aircraft_adsb         109B |######################
aircraft_hostile      114B |########################
route_3wp             116B |########################
drawing_rectangle_itak  118B |########################
drawing_polygon       121B |#########################
marker_icon_set       125B |##########################
drawing_freeform      126B |##########################
pli_stationary        127B |##########################
marker_goto_itak      130B |###########################
pli_webtak            155B |################################
pli_full              164B |##################################
route_itak_3wp        171B |####################################
drawing_telestration  210B |############################################
LoRa MTU              237B |##################################################
```
