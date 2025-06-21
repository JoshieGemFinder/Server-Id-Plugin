package com.joshiegemfinder.serveridplugin;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.slf4j.Logger;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent.ForwardResult;
import com.velocitypowered.api.event.player.configuration.PlayerEnterConfigurationEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.messages.LegacyChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

@Plugin(id = ServerIdPlugin.ID, name = ServerIdPlugin.NAME, version = ServerIdPlugin.VERSION, authors = {"JoshieGemFinder"}, description = "Allows the client to query the id of the server id they're connected to via the worldinfo:world_id packet. Primary difference from other plugins is that it supports querying the world during the configuration phase.")
public class ServerIdPlugin {
	public static final String ID = "server-id";
	public static final String NAME = "Server Id";
	public static final String VERSION = "1.1.0";

	// World ID Channels
	public static final ChannelIdentifier WORLD_ID_CHANNEL = MinecraftChannelIdentifier.create("worldinfo", "world_id");
	public static final ChannelIdentifier LEGACY_WORLD_ID_CHANNEL = new LegacyChannelIdentifier("world_id");
	// Channel to tell the client we can handle world id queries
	public static final ChannelIdentifier CUSTOM_REGISTER_CHANNEL = MinecraftChannelIdentifier.create("minecraft", "register");
	
	public final ProxyServer server;
	public final Logger logger;
	
	@Inject
	public ServerIdPlugin(ProxyServer server, Logger logger) {
		this.server = server;
		this.logger = logger;
	}
	
	@Subscribe
	public void onProxyInitialization(ProxyInitializeEvent event) {
		// so we can intercept world_id messages
		server.getChannelRegistrar().register(WORLD_ID_CHANNEL);
		server.getChannelRegistrar().register(LEGACY_WORLD_ID_CHANNEL);
	}
	
	@Subscribe
	public void onEnterConfiguration(PlayerEnterConfigurationEvent event) {
		Player player = event.player();
		// tell the client that we accept the worldinfo:world_id packet
		// configuration phase was added after the legacy world_id channel was phased out, so don't worry about it here
		player.sendPluginMessage(CUSTOM_REGISTER_CHANNEL, WORLD_ID_CHANNEL.getId().getBytes(StandardCharsets.US_ASCII));
	}

	@Subscribe(priority = 120)
	public void onPluginMessage(PluginMessageEvent event) {
		ChannelIdentifier identifier = event.getIdentifier();
		String id = identifier == null ? null : identifier.getId();
		if(Objects.equals(id, WORLD_ID_CHANNEL.getId())) {
			handleWorldIdMessage(WORLD_ID_CHANNEL, event);
		} else if(Objects.equals(id, LEGACY_WORLD_ID_CHANNEL.getId())) {
			handleWorldIdMessage(LEGACY_WORLD_ID_CHANNEL, event);
		}
	}
	
	public void handleWorldIdMessage(ChannelIdentifier channel, PluginMessageEvent event) {
		ChannelMessageSource source = event.getSource();
		ChannelMessageSink target = event.getTarget();
		
		// intercept player world name requests
		if(source instanceof Player player) {
			if(target instanceof ServerConnection connection) {
				sendPlayerQueryResponse(channel, player, connection, event);
			} else {
				player.getCurrentServer().ifPresent((connection) -> sendPlayerQueryResponse(channel, player, connection, event));
			}
		}
		// intercept and replace any server world name responses to not confuse the client
		else if(source instanceof ServerConnection connection && target instanceof Player player) {
			interceptServerResponse(channel, player, connection, event);
		}
	}
	
	public void sendPlayerQueryResponse(ChannelIdentifier channel, Player player, ServerConnection connection, PluginMessageEvent event) {
		sendWorldIdToPlayer(channel, player, connection, event, false);
	}

	public void interceptServerResponse(ChannelIdentifier channel, Player player, ServerConnection connection, PluginMessageEvent event) {
		sendWorldIdToPlayer(channel, player, connection, event, true);
	}
	
	public void sendWorldIdToPlayer(ChannelIdentifier channel, Player player, ServerConnection connection, PluginMessageEvent event, boolean isComingFromServer) {
		
		/*
		 * Request-response format for compliant mods is
		 * Request: 0 42 0
		 * Response: 0 42 <length> <world id>
		 * 
		 * Unfortunately, some versions of Voxelmap instead expect this:
		 * Request: 0 0 0 42
		 * Response: 42 <length> <world id>
		 */

		var data = event.dataAsInputStream();
		
		int zeroCount = 0;
		
		int magic = 0;
		while(data.available() > 0 && (magic = data.read()) == 0) {
			zeroCount++;
		}
		
		// magic number is missing, something's wrong
		if(magic != 42) {
			return;
		}
		
		boolean isBadVoxelmap;
		if(isComingFromServer) {
			isBadVoxelmap = zeroCount == 0;
		} else {
			isBadVoxelmap = zeroCount == 3;
		}
		
		// if this is neither the standard or voxelmap being naughty, then we've got no clue how to handle it
		if(zeroCount != 1 && !isBadVoxelmap) {
			return;
		}
		
		player.sendPluginMessage(channel, formatResponse(connection.getServerInfo().getName(), isBadVoxelmap));
		
		event.setResult(ForwardResult.handled());
	}
	
	public static byte[] formatResponse(String worldName, boolean isBadVoxelmap) {
		
		// only want one byte per character, as that is the standard for this packet
		byte[] worldNameBytes = worldName.getBytes(StandardCharsets.UTF_8);
		
		ByteArrayOutputStream buf = new ByteArrayOutputStream(isBadVoxelmap ? worldNameBytes.length + 2 : worldNameBytes.length + 3);
		
		if(!isBadVoxelmap) {
			buf.write(0);
		}
		
		buf.write(42);
		
		buf.write(worldNameBytes.length);
		buf.writeBytes(worldNameBytes);
		
		return buf.toByteArray();
	}
}
