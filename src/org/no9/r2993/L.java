package org.no9.r2993;

import android.util.Log;


public class L {
	public static void info(Object o){
		Log.d(">==< USB Controller >==<", String.valueOf(o));
	}

	public static void error(Object o){
		Log.e(">==< USB Controller >==<", String.valueOf(o));
	}
}