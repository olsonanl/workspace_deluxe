package us.kbase.typedobj.util;

public class TypeUtils {
	
	public static void checkString(String s, String sname) {
		if (s == null || s.length() == 0) {
			throw new IllegalArgumentException(sname + " cannot be null or the empty string");
		}
	}
}