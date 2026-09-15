package org.imixs.ai.agent.handler;

import java.util.List;
import java.util.StringTokenizer;
import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;

/**
 * The WorkitemHelper provides methods to clone, compare and sort workitems.
 * 
 * @author rsoika
 * 
 */
public class WorkitemHelper {
	private static Logger logger = Logger.getLogger(WorkitemHelper.class.getName());

	/**
	 * This method tests if a given WorkItem matches a filter expression. The
	 * expression is expected in a column separated list of reg expressions for
	 * Multiple properties. - e.g.:
	 * 
	 * <code>(txtWorkflowGroup:Invoice)($ProcessID:1...)</code>
	 * 
	 * @param workitem - workItem to be tested
	 * @param filter   - combined regex to test different fields
	 * @return - true if filter matches filter expression.
	 */
	public static boolean matches(ItemCollection workitem, String filter) {

		if (filter == null || "".equals(filter.trim()))
			return true;

		// split columns
		StringTokenizer regexTokens = new StringTokenizer(filter, ")");
		while (regexTokens.hasMoreElements()) {
			String regEx = regexTokens.nextToken();
			// remove columns
			regEx = regEx.replace("(", "");
			regEx = regEx.replace(")", "");
			regEx = regEx.replace(",", "");
			// test if ':' found
			if (regEx.indexOf(':') > -1) {
				regEx = regEx.trim();
				// test if regEx contains "
				regEx = regEx.replace("\"", "");
				String itemName = regEx.substring(0, regEx.indexOf(':'));
				regEx = regEx.substring(regEx.indexOf(':') + 1);
				@SuppressWarnings("unchecked")
				List<Object> itemValues = workitem.getItemValue(itemName);
				for (Object aValue : itemValues) {
					if (!aValue.toString().matches(regEx)) {
						logger.fine("Value '" + aValue + "' did not match : " + regEx);
						return false;
					}
				}

			}
		}
		// workitem matches criteria
		return true;
	}
}
