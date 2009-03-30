/*
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2007 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 * $Rev$
 * Last modified by $Author$
 * $Date$
 */
package tigase.server.bosh;

import java.util.List;
import java.util.Queue;
import java.util.TimerTask;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import tigase.server.Command;
import tigase.xml.Element;
import tigase.server.Packet;
import tigase.xmpp.Authorization;
import tigase.xmpp.PacketErrorTypeException;
import tigase.xmpp.StanzaType;

import static tigase.server.bosh.Constants.*;

/**
 * Describe class BoshSession here.
 *
 *
 * Created: Tue Jun  5 18:07:23 2007
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class BoshSession {

  /**
   * Variable <code>log</code> is a class logger.
   */
  private static final Logger log =
    Logger.getLogger("tigase.server.bosh.BoshSession");

	private static final long SECOND = 1000;
	private static final String PRESENCE_ELEMENT_NAME = "presence";
	private static final String MESSAGE_ELEMENT_NAME = "message";
	private static final String IQ_ELEMENT_NAME = "iq";

	private UUID sid = null;
	private Queue<BoshIOService> connections =
    new ConcurrentLinkedQueue<BoshIOService>();
	private Queue<Element> waiting_packets = new ConcurrentLinkedQueue<Element>();
	private BoshSessionCache cache = null;
	private boolean cache_on = false;
	private BoshSessionTaskHandler handler = null;
	private long max_wait = MAX_WAIT_DEF_PROP_VAL;
	private long min_polling = MIN_POLLING_PROP_VAL;
	private long max_inactivity = MAX_INACTIVITY_PROP_VAL;
	private int concurrent_requests = CONCURRENT_REQUESTS_PROP_VAL;
	private int hold_requests = HOLD_REQUESTS_PROP_VAL;
	private long max_pause = MAX_PAUSE_PROP_VAL;
	private String content_type = CONTENT_TYPE_DEF;
	private String domain = null;
	private String sessionId = null;
	private String dataReceiver = null;
	private int[] hashCodes = null;
	/**
	 * <code>current_rid</code> is the table with body rids which are waiting
	 * for replies.
	 */
	private long[] currentRids = null;
	private int rids_head = 0;
	private int rids_tail = 0;
	private long previous_received_rid = -1;

	private boolean terminate = false;
	//private enum TimedTask { EMPTY_RESP, STOP };
	//private Map<TimerTask, TimedTask> task_enum =
	//	new LinkedHashMap<TimerTask, TimedTask>();
	//private EnumMap<TimedTask, TimerTask> enum_task =
	//	new EnumMap<TimedTask, TimerTask>(TimedTask.class);
	private TimerTask waitTimer = null;
	private TimerTask inactivityTimer = null;

	/**
	 * Creates a new <code>BoshSession</code> instance.
	 *
	 *
	 * @param def_domain
	 * @param dataReceiver
	 * @param handler
	 */
	public BoshSession(String def_domain, String dataReceiver,
		BoshSessionTaskHandler handler) {
		this.sid = UUID.randomUUID();
		this.domain = def_domain;
		this.dataReceiver = dataReceiver;
		this.handler = handler;
	}

	public void init(Packet packet, BoshIOService service,
		long max_wait, long min_polling, long max_inactivity,
		int concurrent_requests, int hold_requests, long max_pause,
		Queue<Packet> out_results) {
		String cache_action = packet.getAttribute(CACHE_ATTR);
		if (cache_action != null && cache_action.equals(CacheAction.on.toString())) {
			cache = new BoshSessionCache();
			cache_on = true;
			log.fine("BoshSessionCache set to ON");
		}
		hashCodes = new int[(this.concurrent_requests+1)*5];
		currentRids = new long[(this.concurrent_requests+1)*5];
		for (int i = 0; i < currentRids.length; i++) {
			currentRids[i] = -1;
			hashCodes[i] = -1;
		}
		long wait_l = max_wait;
		String wait_s = packet.getAttribute(WAIT_ATTR);
		if (wait_s != null) {
			try {
				wait_l = Long.parseLong(wait_s);
			} catch (NumberFormatException e) {
				wait_l = max_wait;
			}
		}
		//this.max_wait = Math.min(wait_l, max_wait);
		this.max_wait = wait_l;
		int hold_i = hold_requests;
		String tmp_str = packet.getAttribute(HOLD_ATTR);
		if (tmp_str != null) {
			try {
				hold_i = Integer.parseInt(tmp_str);
			} catch (NumberFormatException e) {
				hold_i = hold_requests;
			}
		}
		tmp_str = packet.getAttribute(RID_ATTR);
		if (tmp_str != null) {
			try {
				previous_received_rid = Long.parseLong(tmp_str);
				currentRids[rids_head++] = previous_received_rid;
			} catch (NumberFormatException e) {	}
		}
		this.hold_requests = Math.max(hold_i, hold_requests);
		if (packet.getAttribute(TO_ATTR) != null) {
			this.domain = packet.getAttribute(TO_ATTR);
		}
		this.min_polling = min_polling;
		this.max_inactivity = max_inactivity;
		this.concurrent_requests = concurrent_requests;
		this.max_pause = max_pause;
		if (packet.getAttribute(CONTENT_ATTR) != null) {
			content_type = packet.getAttribute(CONTENT_ATTR);
		}
		String lang = packet.getAttribute(LANG_ATTR);
		if (lang == null) {
			lang = "en";
		}
		service.setContentType(content_type);
		Element body = new Element(BODY_EL_NAME,
			new String[] {WAIT_ATTR,
										INACTIVITY_ATTR,
										POLLING_ATTR,
										REQUESTS_ATTR,
										HOLD_ATTR,
										MAXPAUSE_ATTR,
										SID_ATTR,
										VER_ATTR,
										FROM_ATTR,
										SECURE_ATTR,
										"xmpp:version",
										"xmlns:xmpp",
										"xmlns:stream"},
			new String[] {Long.valueOf(this.max_wait).toString(),
										Long.valueOf(this.max_inactivity).toString(),
										Long.valueOf(this.min_polling).toString(),
										Integer.valueOf(this.concurrent_requests).toString(),
										Integer.valueOf(this.hold_requests).toString(),
										Long.valueOf(this.max_pause).toString(),
										this.sid.toString(),
										BOSH_VERSION,
										this.domain,
										"true",
										"1.0",
										"urn:xmpp:xbosh",
										"http://etherx.jabber.org/streams"});
		sessionId = UUID.randomUUID().toString();
		body.setAttribute(AUTHID_ATTR, sessionId);
		if (getCurrentRidTail() > 0) {
			body.setAttribute(ACK_ATTR, ""+takeCurrentRidTail());
		}
		body.setXMLNS(BOSH_XMLNS);
		sendBody(service, body);
		//service.writeRawData(body.toString());
		Packet streamOpen = Command.STREAM_OPENED.getPacket(null, null,
			StanzaType.set, UUID.randomUUID().toString(), Command.DataType.submit);
		Command.addFieldValue(streamOpen, "session-id", sessionId);
		Command.addFieldValue(streamOpen, "hostname", domain);
		Command.addFieldValue(streamOpen, LANG_ATTR, lang);
		handler.addOutStreamOpen(streamOpen, this);
		//out_results.offer(streamOpen);
	}

	public String getSessionId() {
		return sessionId;
	}

	public String getDataReceiver() {
		return dataReceiver;
	}

	public void setDataReceiver(String dataReceiver) {
		this.dataReceiver = dataReceiver;
	}

	public UUID getSid() {
		return sid;
	}

	public String getDomain() {
		return domain;
	}

	public void close() {
		terminate = true;
		processPacket(null, null);
	}

	public synchronized void processPacket(Packet packet,
		Queue<Packet> out_results) {

		if (packet != null) {
			if (log.isLoggable(Level.FINEST)) {
				log.finest("[" + connections.size() +
					"] Processing packet: " + packet.toString());
			}
			if (filterInPacket(packet)) {
				waiting_packets.offer(packet.getElement());
			} else {
				if (log.isLoggable(Level.FINEST)) {
					log.finest("[" + connections.size() +
						"] In packet filtered: " + packet.toString());
				}
			}
		}
		if (connections.size() > 0 &&
			(waiting_packets.size() > 0 || terminate)) {
			BoshIOService serv = connections.poll();
			sendBody(serv, null);
		}
	}

	private Pattern[] links_regexs =	{
			Pattern.compile("([^>/\";]|^)(www\\.[^ ]+)", Pattern.CASE_INSENSITIVE),
			Pattern.compile("([^\">;]|^)(http://[^ ]+)", Pattern.CASE_INSENSITIVE),
	};
	private String[] replace_with = {
		"$1&lt;a href=\"http://$2\" target=\"_blank\"&gt;$2&lt;/a&gt;",
		"$1&lt;a href=\"$2\" target=\"_blank\"&gt;$2&lt;/a&gt;",
	};

	private Element applyFilters(Element packet) {
		Element result = packet.clone();
		if (result.getName() == MESSAGE_ELEMENT_NAME) {
			String body =	result.getCData("/message/body");
			if (body != null) {
				int count = 0;
// 				for (Pattern reg: links_regexs) {
// 					body = reg.matcher(body).replaceAll(replace_with[count++]);
// 				}
				result.getChild("body").setCData(body);
			}
		}
		return result;
	}

	private long getCurrentRidTail() {
		synchronized (currentRids) {
			return currentRids[rids_tail];
		}
	}

	private long takeCurrentRidTail() {
		synchronized (currentRids) {
			int idx = rids_tail++;
			if (rids_tail >= currentRids.length) {
				rids_tail = 0;
			}
			return currentRids[idx];
		}
	}

	private void processRid(long rid, List<Element> packets) {
		synchronized (currentRids) {
			if ((previous_received_rid + 1) != rid) {
				log.info("Incorrect packet order, last_rid=" + previous_received_rid
          + ", current_rid=" + rid);
			}
			if (packets != null && packets.size() > 0) {
				StringBuilder sb = new StringBuilder();
				for (Element elem : packets) {
					sb.append(elem.toString());
				}
				hashCodes[rids_head] = sb.toString().hashCode();
			} else {
				hashCodes[rids_head] = -1;
			}
			previous_received_rid = rid;
			currentRids[rids_head++] = rid;
			if (rids_head >= currentRids.length) {
				rids_head = 0;
			}
		}
	}

	private boolean isDuplicateRid(long rid, List<Element> packets) {
		synchronized (currentRids) {
			int hashCode = -1;
			if (packets != null && packets.size() > 0) {
				StringBuilder sb = new StringBuilder();
				for (Element elem : packets) {
					sb.append(elem.toString());
				}
				hashCode = sb.toString().hashCode();
			}
			for (int i = 0; i < currentRids.length; ++i) {
				if (rid == currentRids[i]) {
					return hashCode != hashCodes[i];
				}
			}
		}
		return false;
	}

	private boolean isDuplicateMessage(long rid, List<Element> packets) {
		synchronized (currentRids) {
			int hashCode = -1;
			if (packets != null && packets.size() > 0) {
				StringBuilder sb = new StringBuilder();
				for (Element elem : packets) {
					sb.append(elem.toString());
				}
				hashCode = sb.toString().hashCode();
			}
			if (hashCode == -1) {
				return false;
			}
			for (int i = 0; i < currentRids.length; ++i) {
				if (rid == currentRids[i]) {
					return hashCode == hashCodes[i];
				}
			}
		}
		return false;
	}

	private Element getBodyElem() {
		Element body = new Element(BODY_EL_NAME,
						new String[]{FROM_ATTR, SECURE_ATTR,
							"xmpp:version",
							"xmlns:xmpp",
							"xmlns:stream"},
						new String[]{this.domain, "true",
							"1.0",
							"urn:xmpp:xbosh",
							"http://etherx.jabber.org/streams"});
		body.setXMLNS(BOSH_XMLNS);
		return body;
	}

	private synchronized void sendBody(BoshIOService serv, Element body_par) {
		Element body = body_par;
		if (body == null) {
			body = getBodyElem();
			long rid = takeCurrentRidTail();
			if (rid > 0) {
				body.setAttribute(ACK_ATTR, ""+rid);
			}
			if (waiting_packets.size() > 0) {
				//body.addChild(applyFilters(waiting_packets.poll()));
				body.addChild(waiting_packets.poll());
				while (waiting_packets.size() > 0
					&& body.getChildren().size() < MAX_PACKETS) {
					//body.addChild(applyFilters(waiting_packets.poll()));
					body.addChild(waiting_packets.poll());
				}
			}
		}
		try {
			if (terminate) {
				body.setAttribute("type", StanzaType.terminate.toString());
			}
			handler.writeRawData(serv, body.toString());
			//serv.writeRawData(body.toString());
			//waiting_packets.clear();
			serv.stop();
// 		} catch (IOException e) {
// 			// I call it anyway at the end of method call
// 			//disconnected(null);
// 			log.log(Level.WARNING, "[" + connections.size() +
// 				"] Exception during writing to socket", e);
		} catch (Exception e) {
			log.log(Level.WARNING, "[" + connections.size() +
				"] Exception during writing to socket", e);
		}
		serv.setSid(null);
		disconnected(serv);
		if (waitTimer != null) {
			if (log.isLoggable(Level.FINEST)) {
    			log.finest("Canceling waitTimer: " + getSid());
            }
			handler.cancelTask(waitTimer);
		}
	}

	private void processAutomaticCache(Packet packet) {
		if (packet.getElemName() == PRESENCE_ELEMENT_NAME) {
			cache.addPresence(packet.getElement());
		}
		if (packet.getElemName() == MESSAGE_ELEMENT_NAME) {
			cache.addFromMessage(packet.getElement());
		}
		if (packet.isXMLNS("/iq/query", "jabber:iq:roster")) {
			cache.addRoster(packet.getElement());
		}
		if (packet.isXMLNS("/iq/bind", "urn:ietf:params:xml:ns:xmpp-bind")) {
			cache.set(BoshSessionCache.RESOURCE_BIND_ID,
				Collections.singletonList(packet.getElement()));
		}
	}

	private void processCache(CacheAction action, Packet packet) {
		List<Element> children = packet.getElemChildren(BODY_EL_NAME);
		String cache_id = packet.getAttribute(CACHE_ID_ATTR);
		List<Element> cache_res = null;
		switch (action) {
		case on:
			if (cache == null) {
				cache = new BoshSessionCache();
			}
			cache_on = true;
			log.fine("BoshSessionCache set to ON");
			break;
		case off:
			cache_on = false;
			log.fine("BoshSessionCache set to OFF");
			break;
		case set:
			cache.set(cache_id, children);
			break;
		case add:
			cache.add(cache_id, children);
			break;
		case get:
			cache_res = cache.get(cache_id);
			break;
		case remove:
			cache.remove(cache_id);
			break;
		case get_all:
			cache_res = cache.getAll();
			break;
		default:
			log.warning("Unknown cache action: " + action.toString());
			break;
		}
		if (cache_res != null) {
			waiting_packets.addAll(cache_res);
		}
	}

	private boolean filterOutPacket(Packet packet) {
		if (cache_on && packet.getElemName() == MESSAGE_ELEMENT_NAME) {
			cache.addToMessage(packet.getElement());
		}
		return true;
	}

	private boolean filterInPacket(Packet packet) {
		if (cache_on) {
			processAutomaticCache(packet);
		}
		return true;
	}

	public synchronized void processSocketPacket(Packet packet,
		BoshIOService service, Queue<Packet> out_results) {

		if (log.isLoggable(Level.FINEST)) {
			log.finest("[" + connections.size() +
				"] Processing socket packet: " + packet.toString());
		}

		if (waitTimer != null) {
			if (log.isLoggable(Level.FINEST)) {
    			log.finest("Canceling waitTimer: " + getSid());
            }
			handler.cancelTask(waitTimer);
		}
		if (inactivityTimer != null) {
			if (log.isLoggable(Level.FINEST)) {
    			log.finest("Canceling inactivityTimer: " + getSid());
            }
			handler.cancelTask(inactivityTimer);
		}

		if (packet.getElemName() == BODY_EL_NAME && packet.getXMLNS() == BOSH_XMLNS) {
			List<Element> children = packet.getElemChildren(BODY_EL_NAME);
			boolean duplicate = false;
			if (packet.getAttribute(RID_ATTR) != null) {
				try {
					long rid = Long.parseLong(packet.getAttribute(RID_ATTR));
					if (isDuplicateRid(rid, children)) {
						log.info("Discovered duplicate client connection, trying to close the old one with RID: " + rid);
						Element body = getBodyElem();
						body.setAttribute("type", StanzaType.terminate.toString());
						sendBody(service, body);
						return;
					}
					service.setRid(rid);
					duplicate = isDuplicateMessage(rid, children);
					if (!duplicate) {
						processRid(rid, children);
					}
				} catch (NumberFormatException e) {
					log.warning("Incorrect RID value: " + packet.getAttribute(RID_ATTR));
				}
			}
			service.setContentType(content_type);
			service.setSid(sid);
			connections.offer(service);
			if (!duplicate) {
				if (packet.getType() != null && packet.getType() == StanzaType.terminate) {
					// We are preparing for session termination.
					// Some client send IQ stanzas with private data to store some
					// settings so some confirmation stanzas might be sent back
					// let's give the client a few secs for session termination
					max_inactivity = 2;   // Max pause changed to 2 secs
					terminate = true;
					Packet command = Command.STREAM_CLOSED.getPacket(null, null,
						StanzaType.set, UUID.randomUUID().toString());
					handler.addOutStreamClosed(command, this);
					//out_results.offer(command);
				}
				if (packet.getAttribute(RESTART_ATTR) != null
					&& packet.getAttribute(RESTART_ATTR).equals("true")) {
					log.fine("Found stream restart instruction: " + packet.toString());
					out_results.offer(Command.GETFEATURES.getPacket(null, null,
							StanzaType.get, "restart1", null));
				}
				if (packet.getAttribute(CACHE_ATTR) != null) {
					try {
						CacheAction action =
              CacheAction.valueOf(packet.getAttribute(CACHE_ATTR));
						if (cache_on || (action == CacheAction.on)) {
							processCache(action, packet);
						}
					} catch (IllegalArgumentException e) {
						log.warning("Incorrect cache action: "
							+ packet.getAttribute(CACHE_ATTR));
					}
				} else {
										if (children != null) {
						for (Element el: children) {
							if (el.getXMLNS().equals(BOSH_XMLNS)) {
								el.setXMLNS("jabber:client");
							}
							Packet result = new Packet(el);
							if (filterOutPacket(result)) {
								if (log.isLoggable(Level.FINEST)) {
									log.finest("Sending out packet: " + result.toString());
								}
								out_results.offer(result);
							} else {
								if (log.isLoggable(Level.FINEST)) {
									log.finest("Out packet filtered: " + result.toString());
								}
							}
						}
					}
				}
			} else {
				log.info("Duplicated packet: " + packet.toString());
			}
		} else {
			log.warning("[" + connections.size() +
				"] Unexpected packet from the network: " + packet.toString());
			String er_msg = "Invalid body element";
			if (packet.getElemName() != BODY_EL_NAME) {
				er_msg += ", incorrect root element name, use " + BODY_EL_NAME;
			}
			if (packet.getXMLNS() != BOSH_XMLNS) {
				er_msg += ", incorrect xmlns, use " + BOSH_XMLNS;
			}
			try {
				Packet error = Authorization.BAD_REQUEST.getResponseMessage(
					packet, er_msg, true);
				waiting_packets.add(error.getElement());
				terminate = true;
				Packet command = Command.STREAM_CLOSED.getPacket(null, null,
					StanzaType.set, UUID.randomUUID().toString());
				handler.addOutStreamClosed(command, this);
				//out_results.offer(command);
			} catch (PacketErrorTypeException e) {
				log.info("Error type and incorrect from bosh client? Ignoring...");
			}
		}
		// Send packets waiting in queue...
		processPacket(null, out_results);

		if (connections.size() > hold_requests) {
			BoshIOService serv = connections.poll();
			sendBody(serv, null);
		}

		if (connections.size() > 0 && waiting_packets.size() == 0) {
			if (log.isLoggable(Level.FINEST)) {
    			log.finest("Setting waitTimer for " + max_wait + ": " + getSid());
            }
			waitTimer = handler.scheduleTask(this, max_wait * SECOND);
		}
	}

	public void disconnected(BoshIOService bios) {
		if (bios != null) {
			connections.remove(bios);
		}
		if (inactivityTimer != null) {
			if (log.isLoggable(Level.FINEST)) {
    			log.finest("Canceling inactivityTimer: " + getSid());
            }
			handler.cancelTask(inactivityTimer);
		}
		if (connections.size() == 0) {
			if (log.isLoggable(Level.FINEST)) {
    			log.finest("Setting inactivityTimer for " + max_inactivity + ": " + getSid());
            }
			inactivityTimer = handler.scheduleTask(this, max_inactivity * SECOND);
		}
	}

	public boolean task(Queue<Packet> out_results, TimerTask tt) {
		if (tt == inactivityTimer) {
			if (log.isLoggable(Level.FINEST)) {
    			log.finest("inactivityTimer fired: " + getSid());
            }
			if (waitTimer != null) {
                if (log.isLoggable(Level.FINEST)) {
    				log.finest("Canceling waitTimer: " + getSid());
                }
				handler.cancelTask(waitTimer);
			}
			for (Element packet : waiting_packets) {
				try {
					out_results.offer(Authorization.RECIPIENT_UNAVAILABLE.
									getResponseMessage(new Packet(packet),
									"Bosh = disconnected", true));
				} catch (PacketErrorTypeException e) {
					log.info("Packet processing exception: " + e);
				}
			}
			if (log.isLoggable(Level.FINEST)) {
    			log.finest("Closing session, inactivity timeout expired: " + getSid());
            }
			Packet command = Command.STREAM_CLOSED.getPacket(null, null,
							StanzaType.set, UUID.randomUUID().toString());
			handler.addOutStreamClosed(command, this);
		//out_results.offer(command);
			return true;
		}
		if (tt == waitTimer) {
			if (log.isLoggable(Level.FINEST)) {
    			log.finest("waitTimer fired: " + getSid());
            }
			BoshIOService serv = connections.poll();
			if (serv != null) {
				sendBody(serv, null);
			}
		}
		return false;
	}

}
