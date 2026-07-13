package com.synsenetwork.vanadium.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a config field whose changes only take effect on restart; everything else is live. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface RestartRequired {
}
