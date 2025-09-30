package ru.ravel.lcpedesktop.model

interface Command {
	fun execute()
	fun undo()
}