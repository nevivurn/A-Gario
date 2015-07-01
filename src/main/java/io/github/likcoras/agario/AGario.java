/*
 * Copyright 2015 likcoras
 * 
 * This file is part of A-Gario
 * 
 * A-Gario is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * A-Gario is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with A-Gario.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.github.likcoras.agario;

import java.io.IOException;
import java.util.List;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import lombok.extern.slf4j.Slf4j;
import org.pircbotx.Channel;
import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.User;
import org.pircbotx.UtilSSLSocketFactory;
import org.pircbotx.exception.IrcException;
import org.pircbotx.hooks.Event;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.NickAlreadyInUseEvent;
import org.pircbotx.hooks.events.PrivateMessageEvent;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

@Slf4j
public class AGario extends ListenerAdapter<PircBotX> {
	
	private static final String HELP_MSG =
			BotUtil.addColors("%cCommands: %n@help, @info, @servers, @isup, @link, ~[link], ?[link]");
	
	private final String ownerNick;
	private final String ownerHost;
	
	private final OutputManager out;
	private final List<Handler> handlers;
	
	public static void main(String[] args) {
		try {
			new AGario();
		} catch (IOException | IrcException | HandlerException e) {
			log.error("Startup Error: ", e);
		}
	}
	
	private AGario() throws IOException, IrcException, HandlerException {
		final BotConfig config = BotConfig.getConfig();
		ownerNick = config.getOthers().getOwnerNick();
		ownerHost = config.getOthers().getOwnerHost();
		out = new OutputManager(config);
		handlers = getHandlers();
		configure(handlers, config);
		new PircBotX(configureBot(config)).startBot();
	}
	
	@Override
	public void onNickAlreadyInUse(NickAlreadyInUseEvent<PircBotX> event) {
		log.warn("Nickname is already in use!");
		quit(event.getBot());
	}
	
	@Override
	public void onEvent(Event<PircBotX> event) throws Exception {
		super.onEvent(event);
		try {
			for (final Handler handler : handlers)
				handler.handleEvent(event);
		} catch (final HandlerException e) {
			event.respond("Error!");
			log.error("Error:", e);
		}
	}
	
	@Override
	public synchronized void onPrivateMessage(
			PrivateMessageEvent<PircBotX> event) {
		if (!isOwner(event.getUser()))
			return;
		final String message = event.getMessage();
		final String target = message.length() > 3 ? message.substring(4) : "";
		try {
			if (message.startsWith("add "))
				out.add(target);
			else if (message.startsWith("rem "))
				out.rem(target);
			else if (message.equals("lst"))
				event.respond(out.getList());
			else if (message.startsWith("raw "))
				rawLine(event.getBot(), target);
			else if (message.equals("quit"))
				quit(event.getBot());
		} catch (final IOException e) {
			event.respond("Error!");
			log.error("Privmsg Error: User: " + event.getUser().getNick()
					+ " Message: " + message, e);
		}
	}
	
	@Override
	public void onMessage(MessageEvent<PircBotX> event) {
		final Channel chan = event.getChannel();
		final User user = event.getUser();
		final String message = event.getMessage();
		if (out.isIgnored(user))
			return;
		else if (message.equalsIgnoreCase("@quit") && isOwner(user))
			quit(event.getBot());
		else
			try {
				if (message.equalsIgnoreCase("@help"))
					out.out(chan, user, HELP_MSG);
				else
					doHandle(chan, user, message);
			} catch (final HandlerException | IOException e) {
				event.respond("Error! Ping likcoras!");
				log.error("Message Error: Chan: " + chan.getName() + " User: "
						+ user.getNick() + " Message: " + message, e);
			}
	}
	
	private List<Handler> getHandlers() {
		return ImmutableList.<Handler> builder().add(new LinkHandler())
				.add(new InfoHandler()).add(new ServersHandler())
				.add(new YoutubeHandler()).add(new IsonHandler())
				.add(new ConnectHandler()).build();
	}
	
	private void configure(List<Handler> handlers, BotConfig config)
			throws HandlerException {
		for (final Handler handler : handlers)
			handler.configure(config);
	}
	
	private Configuration<PircBotX> configureBot(BotConfig config) {
		final BotConfig.Server server = config.getServer();
		final BotConfig.Bot bot = config.getBot();
		final Configuration.Builder<PircBotX> builder =
				new Configuration.Builder<PircBotX>()
						.addListener(this)
						.setAutoReconnect(true)
						
						.setName(bot.getNick())
						.setLogin(bot.getLogin())
						.setRealName(bot.getRealname())
						.setVersion(bot.getVersion())
						.setNickservPassword(bot.getPassword())
						
						.setServerHostname(server.getHost())
						.setServerPort(server.getPort())
						.setSocketFactory(
								server.isSsl() ? server.isTrust()
										? new UtilSSLSocketFactory()
												.trustAllCertificates()
										: SSLSocketFactory.getDefault()
										: SocketFactory.getDefault());
		
		for (final String chan : config.getChannels()) {
			final List<String> chanKey =
					Splitter.on(":").trimResults().omitEmptyStrings().limit(2)
							.splitToList(chan);
			if (chanKey.size() < 2)
				builder.addAutoJoinChannel(chan);
			else
				builder.addAutoJoinChannel(chanKey.get(0), chanKey.get(1));
		}
		return builder.buildConfiguration();
	}
	
	private void rawLine(PircBotX bot, String target) {
		log.info("Raw " + target);
		bot.sendRaw().rawLineNow(target, true);
	}
	
	private void quit(PircBotX bot) {
		bot.stopBotReconnect();
		bot.sendIRC().quitServer();
		log.info("Quitted");
	}
	
	private void doHandle(Channel chan, User user, String message)
			throws IOException, HandlerException {
		final StringBuffer responses = new StringBuffer();
		for (final Handler handler : handlers) {
			final String response = handler.getResponse(chan, user, message);
			if (!response.isEmpty())
				responses.append(response + "\n");
		}
		out.out(chan, user, Splitter.on("\n").omitEmptyStrings().trimResults()
				.splitToList(responses));
	}
	
	private boolean isOwner(User user) {
		return ownerNick.equalsIgnoreCase(user.getNick())
				&& ownerHost.equalsIgnoreCase(user.getHostmask());
	}
	
}
