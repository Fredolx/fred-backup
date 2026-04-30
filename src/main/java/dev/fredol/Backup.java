package dev.fredol;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class Backup implements ModInitializer {
	public static final String MOD_ID = "fred_backup";
	public static final String CONFIG_NAME = "fred_backup.json";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final Path BACKUP_DIR = FabricLoader.getInstance().getGameDir().resolve("backups");
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	public Config config;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
			Thread.ofPlatform().name("FredBackup-Scheduler").daemon(true).factory());

	private ScheduledFuture<?> backupTask;
	private static final Set<String> IGNORED_FILES = Set.of(
			"session.lock",
			".DS_Store",
			"Thumbs.db");

	@Override
	public void onInitialize() {
		getConfig();
		ServerLifecycleEvents.SERVER_STARTED.register(this::onStart);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onShutdown);
	}

	private void onStart(MinecraftServer server) {
		backupTask = scheduler.scheduleWithFixedDelay(
				() -> runBackup(server),
				config.intervalMinutes(),
				config.intervalMinutes(),
				TimeUnit.MINUTES);
	}

	private void runBackup(MinecraftServer server) {
		if (!Files.exists(BACKUP_DIR)) {
			try {
				Files.createDirectory(BACKUP_DIR);
			} catch (Exception e) {
				broadcast(server, "Backup failed");
				LOGGER.error("Failed to create backups folder", e);
				return;
			}
		}
		broadcast(server, "Starting backup");
		Path worldPath = server.getWorldPath(LevelResource.ROOT);
		Path destPath = BACKUP_DIR.resolve(LocalDateTime.now().format(STAMP) + ".zip");
		saveWorld(server, true);
		try {
			zipTree(worldPath, destPath);
		} catch (Exception e) {
			tryCleanup(destPath);
			saveWorld(server, false);
			broadcast(server, "Backup failed");
			LOGGER.error("Failed to backup world", e);
			return;
		}
		saveWorld(server, false);
		broadcast(server, "Backup completed successfully");
		try {
			if (pruneOldBackups())
				broadcast(server, "Pruned oldest backup");
		} catch (Exception e) {
			broadcast(server, "Pruning of old backups failed");
			LOGGER.error("Failed to prune old backups", e);
		}
	}

	private void tryCleanup(Path destPath) {
		try {
			Files.deleteIfExists(destPath);
		} catch (Exception e) {
			LOGGER.error("Failed to cleanup partial backup", e);
		}
	}

	private boolean pruneOldBackups() throws IOException {
		var stream = Files
				.list(BACKUP_DIR);
		var files = stream
				.filter(Files::isRegularFile)
				.filter(x -> com.google.common.io.Files.getFileExtension(x.getFileName().toString()).equals("zip"))
				.collect(Collectors.toList());
		stream.close();
		if (config.maxBackups() < 1 || files.size() <= config.maxBackups())
			return false;
		Path oldestFile = null;
		LocalDateTime oldestDate = null;
		for (Path file : files) {
			String fileName = file.getFileName().toString();
			String fileNameWithoutExtension = com.google.common.io.Files.getNameWithoutExtension(fileName);
			LocalDateTime parsedDate = null;
			try {
				parsedDate = LocalDateTime.parse(fileNameWithoutExtension, STAMP);
			} catch (DateTimeParseException exception) {
				continue;
			}
			if (oldestDate == null || parsedDate.isBefore(oldestDate)) {
				oldestDate = parsedDate;
				oldestFile = file;
			}
		}
		if (oldestFile != null) {
			Files.deleteIfExists(oldestFile);
			return true;
		}
		return false;
	}

	private void broadcast(MinecraftServer server, String message) {
		server.execute(() -> {
			server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
		});
	}

	private void saveWorld(MinecraftServer server, boolean disableSaving) {
		server.executeBlocking(() -> {
			server.saveEverything(true, true, true);
			Iterable<ServerLevel> levels = server.getAllLevels();
			for (ServerLevel level : levels) {
				level.noSave = disableSaving;
			}
		});
	}

	private static void zipTree(Path source, Path dest) throws IOException {
		try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(dest)))) {
			out.setLevel(Deflater.NO_COMPRESSION);
			Files.walkFileTree(source, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (IGNORED_FILES.contains(file.getFileName().toString()))
						return FileVisitResult.CONTINUE;
					out.putNextEntry(new ZipEntry(source.relativize(file).toString().replace(File.separatorChar, '/')));
					Files.copy(file, out);
					out.closeEntry();
					return FileVisitResult.CONTINUE;
				}
			});
		}
	}

	private void onShutdown(MinecraftServer server) {
		if (backupTask != null)
			backupTask.cancel(false);
		try {
			scheduler.submit(() -> {
			}).get(2, TimeUnit.MINUTES);
		} catch (TimeoutException e) {
			LOGGER.warn("In-flight backup did not finish within 2 minutes during shutdown.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException e) {
			LOGGER.error("Sentinel task failed", e);
		}
	}

	private void getConfig() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_NAME);
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		if (Files.exists(path)) {
			try {
				config = gson.fromJson(Files.readString(path), Config.class);
			} catch (Exception e) {
				LOGGER.error("Failed to fetch config", e);
				config = new Config();
			}
		} else {
			config = new Config();
			try (BufferedWriter writer = Files.newBufferedWriter(path)) {
				gson.toJson(config, writer);
			} catch (Exception e) {
				LOGGER.error("Failed to write config", e);
			}
		}
	}
}