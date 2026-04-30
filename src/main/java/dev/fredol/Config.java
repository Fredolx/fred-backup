package dev.fredol;

public record Config(int intervalMinutes,
        int maxBackups, Boolean onlyWhenPlayersConnected) {

    public Config {
        if (intervalMinutes <= 0) {
            Backup.LOGGER.warn("Invalid intervalMinutes in config. Defaulting to 15.");
            intervalMinutes = 15;
        }
        if (maxBackups <= 0) {
            Backup.LOGGER.warn("Invalid maxBackups in config. Defaulting to 20.");
            maxBackups = 20;
        }
    }

    public Config() {
        this(15, 20, true);
    }
}
