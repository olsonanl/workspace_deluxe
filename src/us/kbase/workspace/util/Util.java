package us.kbase.workspace.util;

public class Util {
	//TODO move to kbase
	public static void xorNameId(final String name, final Long id, 
			final String type) {
		if (!(name == null ^ id == null)) {
			throw new IllegalArgumentException(String.format(
					"Must provide one and only one of %s name (was: %s) or id (was: %s)",
					type, name, id));
		}
	}

}
