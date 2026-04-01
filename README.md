# hide (Fabric 1.21.11)

A server-side Fabric mod that adds:

- `/hide <player> enable`
- `/hide <player> disable`

When enabled, the target player is removed from other players' tab list and hidden in-world (including equipment/held items, because entity tracking is stopped for viewers).

## Behavior

- Scope: runtime only (not persisted across restart)
- Visibility: hidden player is hidden from other players; hidden player still sees self
- Command access: admin only (`Permissions.COMMANDS_ADMIN`)
- Verification note: use a second account/client to verify world invisibility to others

## Build and test

```powershell
Set-Location "F:\WORKSPACE\hide-template-1.21.11"
.\gradlew.bat test
.\gradlew.bat build
```

## Run server in dev

```powershell
Set-Location "F:\WORKSPACE\hide-template-1.21.11"
.\gradlew.bat runServer
```

## Limitation (important)

This is fully server-side and works with vanilla clients for visual/tab hiding, but it does **not** hide all signals of player existence (for example: sounds, collision, some plugin/mod side channels). If you need "admin vanish" semantics, those extra channels must be handled separately.
