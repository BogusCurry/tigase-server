package tigase.monitor.tasks;

import java.util.Date;
import java.util.HashSet;

import tigase.disteventbus.EventBus;
import tigase.form.Field;
import tigase.form.Form;
import tigase.kernel.beans.Bean;
import tigase.kernel.beans.Inject;
import tigase.monitor.InfoTask;
import tigase.monitor.MonitorComponent;
import tigase.server.monitor.MonitorRuntime;
import tigase.util.DateTimeFormatter;
import tigase.xml.Element;

@Bean(name = "memory-checker-task")
public class MemoryCheckerTask extends AbstractConfigurableTimerTask implements InfoTask {

	private final static DateTimeFormatter dtf = new DateTimeFormatter();

	public final static String HEAP_MEMORY_MONITOR_EVENT_NAME = "HeapMemoryMonitorEvent";

	public final static String NONHEAP_MEMORY_MONITOR_EVENT_NAME = "NonHeapMemoryMonitorEvent";

	@Inject
	private EventBus eventBus;

	private int maxHeapMemUsagePercent = 90;

	private int maxNonHeapMemUsagePercent = 90;

	@Inject
	private MonitorRuntime runtime;

	private final HashSet<String> triggeredEvents = new HashSet<String>();

	@Override
	public Form getCurrentConfiguration() {
		Form form = super.getCurrentConfiguration();

		form.addField(Field.fieldTextSingle("maxHeapMemUsage", String.valueOf(maxHeapMemUsagePercent),
				"Alarm when heap mem usage is bigger than [%]"));

		form.addField(Field.fieldTextSingle("maxNonHeapMemUsage", String.valueOf(maxNonHeapMemUsagePercent),
				"Alarm when non-heap mem usage is bigger than [%]"));

		return form;
	}

	@Override
	public Form getTaskInfo() {
		Form result = new Form("", "Memory Information", "");
		result.addField(Field.fieldTextSingle("heapMemMax", Long.toString(runtime.getHeapMemMax()), "Heap Memory Max"));
		result.addField(Field.fieldTextSingle("heapMemUsed", Long.toString(runtime.getHeapMemUsed()), "Heap Memory Used"));
		result.addField(Field.fieldTextSingle("nonHeapMemMax", Long.toString(runtime.getNonHeapMemMax()), "Non-Heap Memory Max"));
		result.addField(Field.fieldTextSingle("nonHeapMemUsed", Long.toString(runtime.getNonHeapMemUsed()),
				"Non-Heap Memory Used"));
		result.addField(Field.fieldTextSingle("directMemUsed", Long.toString(runtime.getDirectMemUsed()), "Direct Memory Used"));

		return result;
	}

	@Override
	protected void run() {
		float curHeapMemUsagePercent = runtime.getHeapMemUsage();
		if (curHeapMemUsagePercent >= maxHeapMemUsagePercent) {
			Element event = new Element(HEAP_MEMORY_MONITOR_EVENT_NAME, new String[] { "xmlns" },
					new String[] { MonitorComponent.EVENTS_XMLNS });
			event.addChild(new Element("timestamp", "" + dtf.formatDateTime(new Date())));
			event.addChild(new Element("heapMemUsage", Float.toString(curHeapMemUsagePercent)));
			event.addChild(new Element("heapMemMax", Long.toString(runtime.getHeapMemMax())));
			event.addChild(new Element("heapMemUsed", Long.toString(runtime.getHeapMemUsed())));
			event.addChild(new Element("nonHeapMemMax", Long.toString(runtime.getNonHeapMemMax())));
			event.addChild(new Element("nonHeapMemUsed", Long.toString(runtime.getNonHeapMemUsed())));
			event.addChild(new Element("directMemUsed", Long.toString(runtime.getDirectMemUsed())));
			event.addChild(new Element("message", "Heap memory usage is higher than " + maxHeapMemUsagePercent
					+ " and it equals " + curHeapMemUsagePercent));

			if (!triggeredEvents.contains(event.getName())) {
				eventBus.fire(event);
				triggeredEvents.add(event.getName());
			}
		} else {
			triggeredEvents.remove(HEAP_MEMORY_MONITOR_EVENT_NAME);
		}

		float curNonHeapMemUsagePercent = runtime.getNonHeapMemUsage();
		if (curNonHeapMemUsagePercent >= maxNonHeapMemUsagePercent) {
			Element event = new Element(NONHEAP_MEMORY_MONITOR_EVENT_NAME, new String[] { "xmlns" },
					new String[] { MonitorComponent.EVENTS_XMLNS });
			event.addChild(new Element("timestamp", "" + dtf.formatDateTime(new Date())));
			event.addChild(new Element("nonHeapMemUsage", Float.toString(curNonHeapMemUsagePercent)));
			event.addChild(new Element("heapMemMax", Long.toString(runtime.getHeapMemMax())));
			event.addChild(new Element("heapMemUsed", Long.toString(runtime.getHeapMemUsed())));
			event.addChild(new Element("nonHeapMemMax", Long.toString(runtime.getNonHeapMemMax())));
			event.addChild(new Element("nonHeapMemUsed", Long.toString(runtime.getNonHeapMemUsed())));
			event.addChild(new Element("directMemUsed", Long.toString(runtime.getDirectMemUsed())));
			event.addChild(new Element("message", "Non-Heap memory usage is higher than " + maxNonHeapMemUsagePercent
					+ " and it equals " + curHeapMemUsagePercent));

			if (!triggeredEvents.contains(event.getName())) {
				eventBus.fire(event);
				triggeredEvents.add(event.getName());
			}
		} else {
			triggeredEvents.remove(NONHEAP_MEMORY_MONITOR_EVENT_NAME);
		}
	}

	@Override
	public void setNewConfiguration(Form form) {
		Field heapMemUsage = form.get("maxHeapMemUsage");
		if (heapMemUsage != null) {
			this.maxHeapMemUsagePercent = Integer.parseInt(heapMemUsage.getValue());
		}
		Field nonHeapMemUsage = form.get("maxNonHeapMemUsage");
		if (nonHeapMemUsage != null) {
			this.maxNonHeapMemUsagePercent = Integer.parseInt(nonHeapMemUsage.getValue());
		}
		super.setNewConfiguration(form);
	}

}