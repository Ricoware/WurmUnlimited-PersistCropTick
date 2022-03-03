package org.ricoware.wurmunlimited.mods.persistcroptick;

import java.util.Properties;

import org.gotti.wurmunlimited.modloader.ReflectionUtil;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.classhooks.InvocationHandlerFactory;
import org.gotti.wurmunlimited.modloader.interfaces.Configurable;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.PlayerLoginListener;
import org.gotti.wurmunlimited.modloader.interfaces.PlayerMessageListener;
import org.gotti.wurmunlimited.modloader.interfaces.ServerStartedListener;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;
import org.gotti.wurmunlimited.modsupport.ModSupportDb;

import com.wurmonline.server.creatures.Communicator;
import com.wurmonline.server.players.Player;
import com.wurmonline.server.zones.CropTilePoller;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

import javassist.CtPrimitiveType;
import javassist.bytecode.Descriptor;

import com.wurmonline.server.Server;
import com.wurmonline.server.Servers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.ResultSet;

public class persistcroptick implements WurmServerMod, Configurable, Initable, PlayerLoginListener, PlayerMessageListener, ServerStartedListener {
	public static long dbLastPolledTiles = -1;
	private static boolean initialized = false;
	private boolean usePlayerCommand = true;
	private boolean useLoginDisplay = true;

	@Override
	public void configure(Properties properties) {
		usePlayerCommand = Boolean.valueOf(properties.getProperty("usePlayerCommand", Boolean.toString(usePlayerCommand)));
		useLoginDisplay = Boolean.valueOf(properties.getProperty("useLoginDisplay", Boolean.toString(useLoginDisplay)));
	}

	@Override
	public boolean onPlayerMessage(Communicator communicator, String message) {
		if(message != null) {
			if(communicator.player.getPower() > 3 && message.equals("/setCropTickNow")) {
				long fieldGrowthTime = Servers.localServer.getFieldGrowthTime();		
				long currentTime = System.currentTimeMillis();
				long newTickTime = currentTime - fieldGrowthTime + 1000;

				try {
					ReflectionUtil.setPrivateField(null, ReflectionUtil.getField(CropTilePoller.class, "lastPolledTiles"), newTickTime);
				} 
				catch (Exception e) {
					e.printStackTrace();
				}
				communicator.sendNormalServerMessage("Crop tick reset.");
				return true;
			}
			
			if(usePlayerCommand) {
				if (message.startsWith("/chirp") || message.equals("/nextchirp") || message.equals("/nexttick") || message.equals("/nextcrop")) {
					communicator.sendNormalServerMessage(getNextChirpTime());
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void onPlayerLogin(Player player) {
		if( useLoginDisplay) {
			player.getCommunicator().sendSafeServerMessage(getNextChirpTime());
			if(usePlayerCommand) {
				player.getCommunicator().sendSafeServerMessage("  Type /chirp /nextchirp /nexttick or /nextcrop for update.");
			}
		}
	}

	@Override
	public void onServerStarted() {
		try {
			Connection dbconn = ModSupportDb.getModSupportDb();

			// check if the ModSupportDb table exists
			// if not, create the table and update it with the server's last crop poll time
			if (!ModSupportDb.hasTable(dbconn, "PersistCropTick")) {
				// table create
				try (PreparedStatement ps = dbconn.prepareStatement("CREATE TABLE PersistCropTick (lastPolledTiles LONG NOT NULL DEFAULT 0)")) {
					ps.execute();
				}
				catch (SQLException e) {
					throw new RuntimeException(e);
				}

				// get server last poll time
				long lastPolled = ReflectionUtil.getPrivateField(null, ReflectionUtil.getField(CropTilePoller.class, "lastPolledTiles"));
				
				if(lastPolled < 0) {
					lastPolled = Server.getStartTime();
				}

				// update ModSupportDb
				try (PreparedStatement ps2 = dbconn.prepareStatement("insert into PersistCropTick (lastPolledTiles) values (" + lastPolled + ")")) {
					ps2.executeUpdate();
				}
				catch (SQLException e) {
					throw new RuntimeException(e);
				}

				// set static value to stay in sync
				dbLastPolledTiles = lastPolled;
			} 
			else {
				// table already exists
				// get the value stored there
				dbLastPolledTiles = readDBLastPollTime();

				// update the server crop tile poller to use it
				ReflectionUtil.setPrivateField(null, ReflectionUtil.getField(CropTilePoller.class, "lastPolledTiles"), dbLastPolledTiles);
			}
			initialized = true;
			dbconn.close();
		} 
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void init() {
		try {
			HookManager.getInstance().registerHook("com.wurmonline.server.zones.CropTilePoller", "pollCropTiles", Descriptor.ofMethod(CtPrimitiveType.voidType, null), new InvocationHandlerFactory() {
				@Override
				public InvocationHandler createInvocationHandler() {
					return new InvocationHandler() {
						@Override
						public Object invoke(Object object, Method method, Object[] args) throws Throwable {
							// call the crop tile poller
							Object rtnVal = method.invoke(object, args);

							// we want to make sure that the server start code has run first
							if (!initialized)
								return rtnVal;

							// get the current value for the last polled time
							long lastPolled = ReflectionUtil.getPrivateField(null, ReflectionUtil.getField(CropTilePoller.class, "lastPolledTiles"));

							// if the values are different then update the ModSupportDb to stay in sync
							if (dbLastPolledTiles != lastPolled) {
								dbLastPolledTiles = lastPolled;
								try (Connection dbcon = ModSupportDb.getModSupportDb()) {
									try (PreparedStatement ps = dbcon.prepareStatement("update PersistCropTick set lastPolledTiles = " + lastPolled)) {
										ps.executeUpdate();
									}
									catch (SQLException e) {
										throw new RuntimeException(e);
									}
								}
								catch (SQLException e) {
									throw new RuntimeException(e);
								}
							}
							return rtnVal;
						}
					};
				}
			});
		} 
		catch (Exception e) {
			throw new RuntimeException(e);
		}	
	}

	private static long readDBLastPollTime() {
		long lastPolledTiles = (long) 0;
		try (Connection dbcon = ModSupportDb.getModSupportDb()) {
			try (PreparedStatement ps = dbcon.prepareStatement("SELECT lastPolledTiles FROM PersistCropTick limit 1")) {
				ResultSet rs = ps.executeQuery();
				lastPolledTiles = rs.getLong("lastPolledTiles");
			}
			catch (SQLException e) {
				throw new RuntimeException(e);
			}
		} 
		catch (SQLException e) {
			throw new RuntimeException(e);
		}

		return lastPolledTiles;
	}

	private static String getNextChirpTime() {
		long fieldGrowthTime = Servers.localServer.getFieldGrowthTime();		
		long currentTime = System.currentTimeMillis();
		long elapsedSinceLastPoll = currentTime - dbLastPolledTiles;
		long nextChirp = fieldGrowthTime - elapsedSinceLastPoll;
		
		return "Crops will need tending in about " + Server.getTimeFor(nextChirp) + ".";			
	}
}