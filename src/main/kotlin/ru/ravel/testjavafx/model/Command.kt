package ru.ravel.testjavafx.model

interface Command {
	fun execute()
	fun undo()
}