package ru.ravel.lcpedesktop


import groovy.lang.Binding
import groovy.lang.GroovyShell
import ru.ravel.lcpecore.runtime.GroovyExecutor

class GroovyExecutorImpl : GroovyExecutor {
	override fun exec(code: String, bindings: Map<String, Any?>): Any? {
		val binding = Binding(bindings.toMutableMap())
		val shell = GroovyShell(binding)
		val result = shell.evaluate(code)
		if (bindings is MutableMap<String, Any?>) {
			bindings.putAll(binding.variables as Map<out String, Any?>)
		}
		return result
	}
}