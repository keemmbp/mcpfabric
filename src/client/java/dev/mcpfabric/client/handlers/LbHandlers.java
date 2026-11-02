package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;

/**
 * {@code lb.command} — execute a LiquidBounce command through the client's own command manager,
 * bypassing the chat screen, where its "-" prefix commands are processed. Looked up via reflection
 * so the mod keeps compiling (and stays optional) without a LiquidBounce build dependency.
 */
public final class LbHandlers {
	private LbHandlers() {}

	public static void register(RpcRouter router) {
		router.register("lb.command", ctx -> ClientMc.call(() -> {
			requireControl();
			String command = ctx.getString("command").strip();
			if (command.isBlank()) throw RpcException.badRequest("Empty LiquidBounce command.");

			Object manager;
			try {
				Class<?> api = Class.forName("net.ccbluex.liquidbounce.features.command.CommandManager");
				manager = api.getField("INSTANCE").get(null);
			} catch (ClassNotFoundException e) {
				throw RpcException.unavailable("LiquidBounce is not installed (command manager not on the classpath).");
			} catch (NoSuchFieldException | IllegalAccessException e) {
				throw new RpcException("lb_api_unusable", "LiquidBounce API lookup failed: " + e);
			}
			try {
				manager.getClass().getMethod("execute", String.class).invoke(manager, command);
			} catch (java.lang.reflect.InvocationTargetException e) {
				throw new RpcException("lb_command_failed", String.valueOf(e.getCause()));
			} catch (NoSuchMethodException | IllegalAccessException e) {
				throw new RpcException("lb_api_unusable", "LiquidBounce execute failed: " + e);
			}

			JsonObject o = new JsonObject();
			o.addProperty("executed", true);
			return o;
		}));
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}
}
