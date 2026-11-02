package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code baritone.run} — execute a Baritone command through the Baritone API, bypassing the
 * chat interception (`#` prefix), which does not fire when other client mods replace the
 * network handler. Looked up via reflection so the mod keeps compiling (and stays optional)
 * without a Baritone build dependency.
 */
public final class BaritoneHandlers {
	private BaritoneHandlers() {}

	public static void register(RpcRouter router) {
		router.register("baritone.run", ctx -> ClientMc.call(() -> {
			requireControl();
			String command = ctx.getString("command").strip();
			if (command.startsWith("#")) command = command.substring(1);
			if (command.isBlank()) throw RpcException.badRequest("Empty Baritone command.");

			Object commands = commandManager();
			invokeExecute(commands, command);

			JsonObject o = new JsonObject();
			o.addProperty("executed", true);
			return o;
		}));
	}

	/** BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager(). */
	private static Object commandManager() throws RpcException {
		Object provider;
		try {
			Class<?> api = Class.forName("baritone.api.BaritoneAPI");
			provider = api.getMethod("getProvider").invoke(null);
			Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
			return baritone.getClass().getMethod("getCommandManager").invoke(baritone);
		} catch (ClassNotFoundException e) {
			throw RpcException.unavailable("Baritone is not installed (baritone.api not on the classpath).");
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new RpcException("baritone_api_unusable", "Baritone API lookup failed: " + e);
		} catch (java.lang.reflect.InvocationTargetException e) {
			throw new RpcException("baritone_api_unusable", "Baritone API call failed: " + e.getCause());
		}
	}

	/** ICommandManager.execute(String), falling back to the list / array overloads. */
	private static void invokeExecute(Object commands, String command) throws RpcException {
		try {
			Method string = commands.getClass().getMethod("execute", String.class);
			string.invoke(commands, command);
			return;
		} catch (NoSuchMethodException ignored) {
		} catch (IllegalAccessException e) {
			throw new RpcException("baritone_api_unusable", "Baritone execute failed: " + e);
		} catch (java.lang.reflect.InvocationTargetException e) {
			throw new RpcException("baritone_command_failed", String.valueOf(e.getCause()));
		}
		List<String> args = new ArrayList<>(List.of(command.split("\\s+")));
		for (Method m : commands.getClass().getMethods()) {
			if (!m.getName().equals("execute")) continue;
			Class<?>[] params = m.getParameterTypes();
			try {
				if (params.length == 1 && params[0] == List.class) {
					m.invoke(commands, args);
					return;
				}
				if (params.length == 1 && params[0] == String[].class) {
					m.invoke(commands, (Object) args.toArray(new String[0]));
					return;
				}
			} catch (IllegalAccessException e) {
				throw new RpcException("baritone_api_unusable", "Baritone execute failed: " + e);
			} catch (java.lang.reflect.InvocationTargetException e) {
				throw new RpcException("baritone_command_failed", String.valueOf(e.getCause()));
			}
		}
		throw new RpcException("baritone_execute_missing", "No usable ICommandManager.execute overload found.");
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}
}
