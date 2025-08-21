# Manual Testing

To verify that commands handle player-only access correctly, perform the following steps on a Sponge server with the plugin installed.

## Console Verification
1. From the server console, run each of the player-only commands:
   - `/market addstock <id>`
   - `/market buy <id>`
   - `/market create <quantity> <price>`
   - `/market removelisting <id>`
   - `/market blacklist add`
2. Each command should respond with: `This command can only be used by players.`

## Player Verification
1. Join the server as a player.
2. Execute the above commands with valid arguments.
3. Commands should behave normally when executed by a player and continue to function as before.

These steps ensure that both console and player sources are handled correctly.
