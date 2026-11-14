# Device test checklist (nothing here has been run on a phone yet)
1. Install, accept consent, enable service (Guide screen). Chip shows "Protection ON".
2. Set Instagram limit to 1 min. Open Instagram: 5s pause screen appears; Continue works; Never mind goes home.
3. After ~1 min of use: full-screen block overlay with a message. Reopen Instagram: overlay returns immediately.
4. Leave and return within 2 min: no pause screen, opens count unchanged.
5. Lock/unlock phone while in Instagram: usage keeps counting after unlock.
6. Raise limit: puzzle appears. Lower limit: no puzzle. 3 wrong puzzle answers: 10 min lockout.
7. Schedule: set a window covering "now": block overlay says "off-limits until ...".
8. Emergency unlock: once/day, +5 min on the chosen app only; schedules still block.
9. Export, then Import (needs puzzle). Stats show today's usage.
10. Change phone date backward: usage must not reset.
11. While audio/video plays in a limited app, hit the limit: playback should pause under the block screen (apps that ignore audio focus may keep playing; note which app).
12. Use an app across midnight: usage before midnight stays on the old day (check Stats).
13. During the 5s pause screen, open the floating-button panel and close it: the pause screen must still be there.
14. Set the phone date or time forward or back by hours: usage and schedules must not change (heals after 24 h; a reboot is a known way around it).
15. Open a share sheet or a permission dialog inside a limited app, then return: time keeps counting.
16. Raise the default limit with reduction on: the card should show the new value, not a lower one.
17. Turn the accessibility service off, open Pausely: a red "Protection is OFF" banner shows at the top and the chip is red.
18. Turn TalkBack on: overlays announce a title, day buttons say day + selected, and puzzles are math only.
19. Switch the phone language/24-hour setting: times follow the 24-hour setting.
20. Open Stats: "Recent sessions" lists each counted session with duration and why it ended; the total should match the sum of today's sessions.
21. With an app over its limit the home card reads "Limit reached · … used today (limit …)" in red.
22. Quick panel: tap -5 min repeatedly. It stops at 5 min (button greys out), shows a toast each time, and an Undo button appears for 10 s next to the app.
23. Double-tap Continue on the pause screen: time counts once and the block still arrives on schedule.
24. Stats: the "per active day" average only divides by days with at least 1 minute of use.
25. Schedule card says "Blocks 10:00 pm - 7:00 am", not "Blocked", while outside the window.
26. Add App picker: your home-screen app (launcher), the phone/dialer and Android Settings are not listed, so you can never lock yourself out of them.
27. Quick panel: tap -5 min then Undo: the limit returns to exactly what it was, including any gradual-reduction progress.
28. With gradual reduction on for a few weeks: lowering the default limit never makes apps' effective limit go UP; raising it asks for a puzzle.
29. Puzzle: tapping Check with an empty answer does nothing (not a failed attempt). Tapping a switch twice quickly opens only one puzzle.
30. Schedules: choosing the same start and end time shows a message and does not save.
31. Home > + Add app: full-screen picker with icons, a Suggested group, search, tick circles and an "Add (n)" button; home/dialer/Settings never appear.
32. Settings > Change default: ANY change (up or down) asks for a puzzle; saving the same value does nothing.
33. Settings: "About the developer" sits at the bottom of the screen even on tall phones.
34. Math puzzle: on-screen keypad (digits, plus/minus, delete), progress dots, wrong answer shakes in red; Check on empty does nothing.
35. Floating button: new quick panel with a handle, close button, per-app progress bar, -5 min / Undo, Pause screen and Reduction toggles, Open Pausely.
36. Pause screen: tap "Never mind", then reopen the app within 2 minutes: the pause screen must appear again (no free pass). Leaving mid-pause also shows it again.
37. Use an app for a while, leave and return within 2 minutes: no pause screen (the grace still works for really used sessions).
38. Block screen shows the Emergency hint only while the emergency unlock is still unused today.
39. First launch: after "I understand and agree" the setup guide opens if protection is off.
40. Picker: rotate the phone mid-selection: ticks and search text survive; the keyboard's search key hides the keyboard; adding shows a toast with the default limit.
41. Home: ring subtitle says "of Xm today"; app cards have a "›" hint and open the options menu.
42. Any puzzle shows a one-line reason under the title.
