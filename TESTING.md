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
