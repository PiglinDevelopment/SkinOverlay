# SkinOverlay
This plugin allows you to change your skin's clothes without opening graphical editor and rejoining!

## Commands
- /wear [\<targets\>] \<overlay\> where `<overlay>` is file name in `/plugins/SkinOverlay/<overlay>.png` (without `.png`)
- /wear [\<targets\>] \<url\> where `<url>` is a direct link to an overlay
[\<targets\>] is not required if you don't have a `skinoverlay.wear.others` permission.

## Permissions
- skinoverlay.wear - The command `/wear`
- skinoverlay.wear.others - Wear other players: `/wear <targets> ...`. Supports player names, UUIDs and selectors
- skinoverlay.wear.url - Wear overlays from custom URLs
- skinoverlay.overlay.\<overlay name\> - Use the overlay
- skinoverlay.clear - Remove the overlay
