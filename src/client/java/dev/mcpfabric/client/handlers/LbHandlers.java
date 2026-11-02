package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.StringJoiner;

/**
 * {@code lb.command} / {@code lb.query} — drive and observe LiquidBounce through the client's own
 * command manager and module registry. LB processes "-" commands at the chat screen, so injected
 * chat messages never reach it; calling the command manager directly bypasses that. Everything is
 * looked up via reflection so the mod keeps compiling (and stays optional) without a LiquidBounce
 * build dependency.
 */
public final class LbHandlers {
	private LbHandlers() {}

	private static final String COMMAND_MANAGER = "net.ccbluex.liquidbounce.features.command.CommandManager";
	private static final String MODULE_MANAGER = "net.ccbluex.liquidbounce.features.module.ModuleManager";

	public static void register(RpcRouter router) {
		router.register("lb.command", ctx -> ClientMc.call(() -> {
			requireControl();
			Object manager = commandManager();
			String command = ctx.getString("command").strip();
			if (command.isBlank()) throw RpcException.badRequest("Empty LiquidBounce command.");
			try {
				manager.getClass().getMethod("execute", String.class).invoke(manager, command);
			} catch (InvocationTargetException e) {
				throw new RpcException("lb_command_failed", String.valueOf(e.getCause()));
			} catch (NoSuchMethodException | IllegalAccessException e) {
				throw new RpcException("lb_api_unusable", "LiquidBounce execute failed: " + e);
			}
			JsonObject o = new JsonObject();
			o.addProperty("executed", true);
			return o;
		}));

		router.register("lb.query", ctx -> ClientMc.call(() -> {
			requireControl();
			return switch (ctx.optString("what", "modules")) {
				case "commands" -> commandCatalog(commandManager());
				case "modules" -> moduleStates();
				case "settings" -> moduleSettings(ctx.optString("module", null));
				default -> throw RpcException.badRequest("what must be commands, modules or settings.");
			};
		}));
	}

	/** CommandManager.INSTANCE, or a clean error when LiquidBounce is absent. */
	private static Object commandManager() throws RpcException {
		try {
			Class<?> api = Class.forName(COMMAND_MANAGER);
			return api.getField("INSTANCE").get(null);
		} catch (ClassNotFoundException e) {
			throw RpcException.unavailable("LiquidBounce is not installed (command manager not on the classpath).");
		} catch (NoSuchFieldException | IllegalAccessException e) {
			throw new RpcException("lb_api_unusable", "LiquidBounce API lookup failed: " + e);
		}
	}

	/** Top-level commands with their direct sub-command / argument names, from the Brigadier dispatcher. */
	private static JsonObject commandCatalog(Object manager) throws RpcException {
		try {
			Method getDispatcher = manager.getClass().getDeclaredMethod("getDispatcher");
			getDispatcher.setAccessible(true);
			Object dispatcher = getDispatcher.invoke(manager);
			Object root = dispatcher.getClass().getMethod("getRoot").invoke(dispatcher);

			JsonArray commands = new JsonArray();
			for (Object node : children(root)) {
				JsonObject c = new JsonObject();
				c.addProperty("name", String.valueOf(get(node, "getName")));
				StringJoiner args = new StringJoiner(", ");
				for (Object child : children(node)) args.add(String.valueOf(get(child, "getName")));
				if (args.length() > 0) c.addProperty("args", args.toString());
				commands.add(c);
			}
			JsonObject o = new JsonObject();
			o.add("commands", commands);
			return o;
		} catch (InvocationTargetException e) {
			throw new RpcException("lb_api_unusable", "LiquidBounce dispatcher read failed: " + e.getCause());
		} catch (ReflectiveOperationException e) {
			throw new RpcException("lb_api_unusable", "LiquidBounce dispatcher lookup failed: " + e);
		}
	}

	/** Every registered module with its live enabled state. */
	private static JsonObject moduleStates() throws RpcException {
		Object manager;
		try {
			manager = Class.forName(MODULE_MANAGER).getField("INSTANCE").get(null);
		} catch (ClassNotFoundException e) {
			throw RpcException.unavailable("LiquidBounce is not installed (module manager not on the classpath).");
		} catch (NoSuchFieldException | IllegalAccessException e) {
			throw new RpcException("lb_api_unusable", "LiquidBounce module manager lookup failed: " + e);
		}
		JsonArray modules = new JsonArray();
		for (Object module : iterableOf(manager)) {
			JsonObject m = new JsonObject();
			m.addProperty("name", String.valueOf(get(module, "getName")));
			m.addProperty("enabled", Boolean.TRUE.equals(get(module, "getEnabled")));
			modules.add(m);
		}
		JsonObject o = new JsonObject();
		o.addProperty("count", modules.size());
		o.add("modules", modules);
		return o;
	}

	/** The settings (child values) of one module by name. */
	private static JsonObject moduleSettings(String wanted) throws RpcException {
		if (wanted == null || wanted.isBlank()) throw RpcException.badRequest("'module' is required for what=settings.");
		for (Object module : iterableOf(moduleManager())) {
			if (!String.valueOf(get(module, "getName")).equalsIgnoreCase(wanted)) continue;
			JsonArray settings = new JsonArray();
			for (Object value : iterableOf(inner(module))) {
				JsonObject s = new JsonObject();
				s.addProperty("name", String.valueOf(get(value, "getName")));
				s.addProperty("type", String.valueOf(get(value, "getValueType")));
				try {
					Method inner = value.getClass().getDeclaredMethod("getInner$net_ccbluex_liquidbounce");
					inner.setAccessible(true);
					s.addProperty("value", String.valueOf(inner.invoke(value)));
				} catch (ReflectiveOperationException ignored) {
					// a group rather than a leaf value; the name/type still identify it
				}
				settings.add(s);
			}
			JsonObject o = new JsonObject();
			o.addProperty("module", wanted);
			o.add("settings", settings);
			return o;
		}
		throw RpcException.notFound("No LiquidBounce module named " + wanted + ".");
	}

	private static Object moduleManager() throws RpcException {
		try {
			return Class.forName(MODULE_MANAGER).getField("INSTANCE").get(null);
		} catch (ReflectiveOperationException e) {
			throw new RpcException("lb_api_unusable", "LiquidBounce module manager lookup failed: " + e);
		}
	}

	/** ValueGroup's child values (settings); also works for Value objects holding collections. */
	private static Iterable<?> inner(Object valueGroup) throws RpcException {
		try {
			Method inner = valueGroup.getClass().getMethod("getInner$net_ccbluex_liquidbounce");
			inner.setAccessible(true);
			return iterableOf(inner.invoke(valueGroup));
		} catch (ReflectiveOperationException e) {
			throw new RpcException("lb_api_unusable", "LiquidBounce settings read failed: " + e);
		}
	}

	/** Public no-arg getter via reflection, with reflective exceptions folded into RpcException. */
	private static Object get(Object target, String getter) throws RpcException {
		try {
			return target.getClass().getMethod(getter).invoke(target);
		} catch (InvocationTargetException e) {
			throw new RpcException("lb_api_unusable", getter + " failed: " + e.getCause());
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new RpcException("lb_api_unusable", getter + " lookup failed: " + e);
		}
	}

	private static Iterable<?> iterableOf(Object collection) throws RpcException {
		if (collection instanceof Iterable<?> it) return it;
		throw new RpcException("lb_api_unusable", "Expected a collection, got " + collection.getClass().getSimpleName());
	}

	private static Collection<?> children(Object node) throws RpcException {
		try {
			return (Collection<?>) node.getClass().getMethod("getChildren").invoke(node);
		} catch (ReflectiveOperationException e) {
			throw new RpcException("lb_api_unusable", "Brigadier node read failed: " + e);
		}
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}
}

