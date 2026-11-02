package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.handlers.support.Gates;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;

/**
 * {@code server.commands} — enumerate the command tree the connected SERVER synced to this client
 * for tab-completion (Brigadier dispatcher built from the Commands packet). This is the same data
 * every vanilla client receives; it just makes it readable. Reflection-based so it survives
 * mapping shifts, with a recursive field scan to locate the dispatcher.
 */
public final class ServerCommandsHandler {
	private ServerCommandsHandler() {}

	private static final int MAX_NODES = 600;
	private static final int MAX_DEPTH = 3;
	private int visited;

	public static void register(RpcRouter router) {
		router.register("server.commands", ctx -> ClientMc.call(() -> {
			Gates.vision();
			Object dispatcher = findDispatcher();
			if (dispatcher == null) {
				throw RpcException.unavailable("No Brigadier dispatcher found on the client network handler.");
			}
			return new ServerCommandsHandler().walkRoot(dispatcher, ctx.optInt("maxNodes", MAX_NODES));
		}));
	}

	/** The client's connection object: LocalPlayer.connection() method or the `connection` field. */
	private static Object connection() throws RpcException {
		Object player = ClientMc.player();
		try {
			return player.getClass().getMethod("connection").invoke(player);
		} catch (NoSuchMethodException ignored) {
		} catch (IllegalAccessException | InvocationTargetException e) {
			throw new RpcException("server_commands_failed", "connection() call failed: " + e);
		}
		for (Class<?> c = player.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				if (f.getName().equals("connection") || f.getType().getSimpleName().endsWith("PacketListener")) {
					f.setAccessible(true);
					try {
						Object conn = f.get(player);
						if (conn != null) return conn;
					} catch (IllegalAccessException ignored) {
					}
				}
			}
		}
		throw new RpcException("server_commands_failed", "no connection field on the local player.");
	}

	/**
	 * Scan the connection object's fields (and its superclasses) for the Brigadier dispatcher the
	 * client builds from the server's Commands packet; fall back to the suggestion provider.
	 */
	private static Object findDispatcher() throws RpcException {
		Object conn = connection();
		Object found = scanForDispatcher(conn);
		if (found != null) return found;
		try {
			for (Method m : conn.getClass().getMethods()) {
				if (!m.getName().toLowerCase().contains("suggestion") || m.getParameterCount() != 0) continue;
				Object provider = m.invoke(conn);
				if (provider == null) continue;
				found = scanForDispatcher(provider);
				if (found != null) return found;
			}
		} catch (IllegalAccessException | InvocationTargetException e) {
			throw new RpcException("server_commands_failed", "suggestion provider scan failed: " + e);
		}
		return null;
	}

	private static Object scanForDispatcher(Object holder) {
		for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				if (f.getType().getName().equals("com.mojang.brigadier.CommandDispatcher")) {
					f.setAccessible(true);
					try {
						return f.get(holder);
					} catch (IllegalAccessException ignored) {
					}
				}
			}
		}
		return null;
	}

	private JsonObject walkRoot(Object dispatcher, int maxNodes) throws RpcException {
		try {
			visited = 0;
			Object root = dispatcher.getClass().getMethod("getRoot").invoke(dispatcher);
			JsonArray commands = new JsonArray();
			for (Object node : children(root)) walk(node, commands, 0, maxNodes);
			JsonObject o = new JsonObject();
			o.addProperty("total", commands.size());
			o.add("commands", commands);
			return o;
		} catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
			throw new RpcException("server_commands_failed", "tree walk failed: " + e);
		}
	}

	private void walk(Object node, JsonArray out, int depth, int maxNodes) throws RpcException {
		if (visited >= maxNodes || depth > MAX_DEPTH) return;
		visited++;
		try {
			JsonObject c = new JsonObject();
			c.addProperty("name", String.valueOf(node.getClass().getMethod("getName").invoke(node)));
			JsonArray args = new JsonArray();
			JsonArray subs = new JsonArray();
			for (Object child : children(node)) {
				String childName = String.valueOf(child.getClass().getMethod("getName").invoke(child));
				if (child.getClass().getSimpleName().contains("LiteralCommandNode")) {
					subs.add(childName); // a real subcommand, walk it
				} else {
					args.add(childName); // an argument placeholder
				}
			}
			if (args.size() > 0) c.add("args", args);
			if (subs.size() > 0) c.add("subs", subs);
			if (args.size() > 0 || subs.size() > 0 || depth == 0) out.add(c);
			for (Object child : children(node)) {
				if (child.getClass().getSimpleName().contains("LiteralCommandNode")) walk(child, out, depth + 1, maxNodes);
			}
		} catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
			throw new RpcException("server_commands_failed", "node read failed: " + e);
		}
	}

	@SuppressWarnings("unchecked")
	private static Collection<?> children(Object node) throws RpcException {
		try {
			return (Collection<?>) node.getClass().getMethod("getChildren").invoke(node);
		} catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
			throw new RpcException("server_commands_failed", "children read failed: " + e);
		}
	}
}
