package net.aquadc.properties.fx.sqlSample

import com.jfoenix.controls.JFXListCell
import com.jfoenix.controls.JFXListView
import javafx.application.Application
import javafx.beans.binding.Bindings
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Stage
import net.aquadc.properties.fx.fx
import net.aquadc.properties.map
import net.aquadc.properties.mapWith
import net.aquadc.properties.sql.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.util.concurrent.Callable


class SqliteApp : Application() {

    private val connection = DriverManager.getConnection("jdbc:sqlite:sample.db").also(::createNeededTables)
    private val sess = JdbcSqliteSession(connection).also(::fillIfEmpty)

    override fun start(stage: Stage) {
        stage.scene = Scene(
                HBox().apply {

                    val hBox = this

                    val listView = JFXListView<Human>().apply {
                        items = FXCollections.observableArrayList(sess.select(HumanTable).value)
                        setCellFactory(::createListCell)
                        prefWidthProperty().bind(hBox.widthProperty().multiply(.4))
                    }
                    children += listView

                    children += VBox().apply {
                        prefWidthProperty().bind(hBox.widthProperty().multiply(.6))

                        padding = Insets(10.0, 10.0, 10.0, 10.0)

                        val selProp: ReadOnlyObjectProperty<Human?> = listView.selectionModel.selectedItemProperty()
                        children += Label().apply {
                            val nameProp = SimpleStringProperty()
                            selProp.addListener { _, _, it ->
                                nameProp.unbind()
                                if (it == null) nameProp.set("")
                                else nameProp.bind(it.nameProp.fx())
                            }
                            textProperty().bind(nameProp)
                        }

                        children += Label().apply {
                            val conditionersProp = SimpleStringProperty()
                            selProp.addListener { _, _, sel ->
                                conditionersProp.unbind()
                                if (sel == null) {
                                    conditionersProp.set("none")
                                } else {
                                    conditionersProp.bind(sel.carsProp.map {
                                        "Air conditioner(s) in car(s): [\n" +
                                                it.map { it.conditionerModelProp.value + '\n' }.joinToString() + ']'
                                    }.fx())
                                }
                            }
                            textProperty().bind(conditionersProp)
                        }
                    }

                },
                500.0, 400.0)
        stage.show()
    }

    private fun createListCell(lv: ListView<Human>): JFXListCell<Human> {
        val cell = object : JFXListCell<Human>() {
            override fun updateItem(item: Human?, empty: Boolean) {
                textProperty().unbind()
                super.updateItem(item, empty)
                if (item != null && !empty) {
                    graphic = null
                    textProperty().bind(item.nameProp.mapWith(item.surnameProp) { n, s -> "$n $s" }.fx())
                }
            }
        }
        cell.setOnMouseClicked { ev -> if (cell.isEmpty) ev.consume() }
        return cell
    }

    override fun stop() {
        connection.close()
    }

    companion object {
        @JvmStatic fun main(args: Array<String>) {
            launch(SqliteApp::class.java)
        }
    }

}


private inline fun <T, R> ObservableValue<T>.map(crossinline transform: (T) -> R) =
        Bindings.createObjectBinding(Callable<R> { transform(value) }, this)


private fun createNeededTables(conn: Connection) {
    Tables.forEach { table ->
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='${table.name}'").use {
                if (it.next()) {
                    println("the table `${table.name}` already exists")
                } else {
                    create(statement, table)
                    println("table `${table.name}` was created")
                }
            }
        }
    }
}

private fun create(statement: Statement, table: Table<*, *>) {
    val sb = StringBuilder("CREATE TABLE ").append(table.name).append(" (")
    val idCol = table.idCol
    check(table.columns.isNotEmpty())
    table.columns.forEach { col ->
        sb.append(col.name).append(' ').append(Java2SQLite[col.javaType]!!)
        if (col === idCol) sb.append(" PRIMARY KEY")
        else if (!col.isNullable) sb.append(" NOT NULL")
        sb.append(", ")
    }
    sb.setLength(sb.length - 2) // trim last comma
    sb.append(");")

    statement.execute(sb.toString())
}

private fun fillIfEmpty(session: Session) {
    if (session.count(HumanTable).value == 0L) {
        session.transaction { transaction ->
            transaction.insertHuman("Stephen", "Hawking")
            val relativist = transaction.insertHuman("Albert", "Einstein")
            transaction.insertHuman("Dmitri", "Mendeleev")
            val electrician = transaction.insertHuman("Nikola", "Tesla")

            // don't know anything about their friendship, just a sample
            transaction.insert(FriendTable,
                    FriendTable.LeftId - relativist.primaryKey, FriendTable.RightId - electrician.primaryKey
            )

            val car = transaction.insertCar(electrician)
            car.conditionerModelProp.value = "the coolest air cooler"
        }
    }
}
