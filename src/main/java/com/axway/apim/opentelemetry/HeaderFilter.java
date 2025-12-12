package com.axway.apim.opentelemetry;

import java.util.HashSet;
import java.util.Set;

public class HeaderFilter {

	static final String CONFIG_FILTER_CASE_INSENSITIVE = "DT_AXWAY_FILTER_CI";
	static final String CONFIG_FILTER_INCOMING_HEADERS = "DT_AXWAY_FILTER_IN_HEADERS";
	static final String CONFIG_FILTER_OUTGOING_HEADERS = "DT_AXWAY_FILTER_OUT_HEADERS";

	
	public static boolean matchRequestFilter(String name) {
		if (incomingFilter == null) {
			return false;
		}
		return matchFilter(name, incomingFilter);
	}

	public static boolean matchResponseFilter(String name) {
		if (outgoingFilter == null) {
			return false;
		}
		return matchFilter(name, outgoingFilter);
	}
	
	private static boolean matchFilter(String name, Set<String> filterValues) {
		if (name == null) {
			return false;
		}
		return filterValues.contains(useCaseInsensitive ? name.toLowerCase() : name);
	}

	private static final boolean getEnvBoolean(String name, boolean defaultValue) {
		try {
			String value = System.getenv(name);
			if (value != null) {
				return Boolean.parseBoolean(value);
			}
		} catch (Throwable ex) {
			System.err.println("Error - unable process variable " + name + ":"+ex.getMessage());
		}
		return defaultValue;
	}

	private static final Set<String> getEnvFilterList(String name, boolean normalize) {
		try {
			String value = System.getenv(name);
			if (value != null) {
				Set<String> filter = new HashSet<String>();
				String filterValues[] = value.split(":");
				for (String rawItem: filterValues) {
					String item = rawItem.trim();
					if (normalize) {
						item = item.toLowerCase();
					}
					if (!item.isBlank()) {
						filter.add(item);
					}
				}
				if (!filter.isEmpty()) {
					return filter;
				}
			}
		} catch (Throwable ex) {
			System.err.println("Error - unable process variable " + name + ":"+ex.getMessage());
		}
		return null;
	}

	static final boolean useCaseInsensitive;
	static final Set<String> incomingFilter;
	static final Set<String> outgoingFilter;

	static {
		useCaseInsensitive = getEnvBoolean(CONFIG_FILTER_CASE_INSENSITIVE, true);
		incomingFilter = getEnvFilterList(CONFIG_FILTER_INCOMING_HEADERS, useCaseInsensitive);
		outgoingFilter = getEnvFilterList(CONFIG_FILTER_OUTGOING_HEADERS, useCaseInsensitive);
	}
}
