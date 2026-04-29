package dev.fredol;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

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

	@Override
	public void onInitialize() {
		getConfig();
		ServerLifecycleEvents.SERVER_STARTED.register(this::onStart);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onShutdown);
	}

	private void onStart(MinecraftServer server) {
		backupTask = scheduler.scheduleAtFixedRate(
				() -> runBackup(server),
				config.intervalMinutes(),
				config.intervalMinutes(),
				TimeUnit.MINUTES);
	}

	private void runBackup(MinecraftServer server) {
		if (!Files.exists(BACKUP_DIR)) {
			try {
				Files.createDirectory(BACKUP_DIR);
			} catch (IOException e) {
				LOGGER.error("Failed to create backups folder", e);
				return;
			}
		}
		Path worldPath = server.getWorldPath(LevelResource.ROOT);
		Path destPath = BACKUP_DIR.resolve(LocalDateTime.now().format(STAMP) + ".zip");
		saveWorld(server, true);
		try {
			zipTree(worldPath, destPath);
		} catch (IOException e) {
			LOGGER.error("Failed to backup world", e);
		}
		saveWorld(server, false);
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
			out.setLevel(Deflater.BEST_SPEED);
			Files.walkFileTree(source, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (file.getFileName().toString().equals("session.lock"))
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
		if (backupTask == null || backupTask.isCancelled())
			return;
		backupTask.cancel(false);
		LOGGER.info("Backup scheduled task cancelled.");
	}

	private void getConfig() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_NAME);
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		if (Files.exists(path)) {
			try {
				config = gson.fromJson(Files.readString(path), Config.class);
			} catch (JsonSyntaxException | IOException e) {
				LOGGER.error("Failed to fetch config", e);
			}
		} else {
			config = new Config(30);
			try (BufferedWriter writer = Files.newBufferedWriter(path)) {
				gson.toJson(config, writer);
			} catch (IOException e) {
				LOGGER.error("Failed to write config", e);
			}
		}
	}
}