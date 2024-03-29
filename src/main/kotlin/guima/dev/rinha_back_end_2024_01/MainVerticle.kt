package guima.dev.rinha_back_end_2024_01

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.Tuple
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter


class MainVerticle : AbstractVerticle() {

  private lateinit var dbClient: Pool

  override fun start(startPromise: Promise<Void>) {

    val DB_HOST = System.getenv("DB_HOST") ?: "localhost"
    val HTTP_PORT = (System.getenv("HTTP_PORT") ?: "8080").toInt()
    val POOL_SIZE = (System.getenv("POOL_SIZE") ?: "5").toInt()

    val pgConnectOptions = PgConnectOptions()
      .setPort(5432)
      .setHost(DB_HOST)
      .setDatabase("rinha")
      .setUser("root")
      .setPassword("rinha")
    val poolOptions = PoolOptions().setMaxSize(POOL_SIZE)

    dbClient = Pool.pool(vertx, pgConnectOptions, poolOptions)

    val router = Router.router(vertx)
      .also {
        it.route().handler(BodyHandler.create())
        it.post("/clientes/:id/transacoes").handler(this::createTransaction)
        it.get("/clientes/:id/extrato").handler(this::getTransactions)
      }

    vertx
      .createHttpServer()
      .requestHandler(router)
      .listen(HTTP_PORT) { http ->
        if (http.succeeded()) {
          startPromise.complete()
          println("HTTP server started on port 8888")
        } else {
          startPromise.fail(http.cause())
        }
      }
  }

  private fun createTransaction(context: RoutingContext) {
    val clientId: Int
    var amount: Int
    var type: String
    var description: String

    try {
      clientId = context.pathParam("id").toInt()
      context.body().asJsonObject()
        .also {
          val amountStr =  it.getString("valor")
          if (amountStr.contains(".")) {
            context.fail(422)
            return
          }
          amount = amountStr.toInt()
          type = it.getString("tipo")
          description = it.getString("descricao")
        }
    } catch (e: Exception) {
      context.fail(422, e)
      return
    }

    if (amount <= 0 ||
        (type != "c" && type != "d") ||
        description.trim().isEmpty() ||
        description.length > 10) {
      context.fail(422)
      return
    }

    //Getting the current client data
    dbClient.withTransaction { tx ->
      tx.preparedQuery("SELECT * FROM clients WHERE id = $1 FOR UPDATE")
      .execute(Tuple.of(clientId))
      .compose { clientResult ->
        if (clientResult.size() == 0) {
          context.fail(404)
          return@compose Future.failedFuture("Client doesn't exists")
        }

        val client = clientResult.iterator().next()
        val accountLimit = client.getInteger("account_limit")
        val balance = client.getInteger("balance")

        var currentBalance = 0
        if (type == "d") {
          currentBalance = balance - amount
          if (currentBalance < - accountLimit) {
            context.fail(422)
            return@compose Future.failedFuture("Current balance is not enough")
          }
        }

        if (type == "c") {
          currentBalance = balance + amount
        }

        //Update and insert
        tx.preparedQuery("UPDATE clients SET balance = $1 WHERE id = $2")
          .execute(Tuple.of(currentBalance, clientId))
          .compose { Future.succeededFuture(mapOf("limite" to accountLimit, "saldo" to currentBalance)) }
      }
      .compose { mapResponse ->
        tx.preparedQuery("INSERT INTO transactions(amount, type, description, client_id) VALUES($1, $2, $3, $4)")
          .execute(Tuple.of(amount, type, description, clientId))
          .compose { Future.succeededFuture(mapResponse) }
      }
      .onSuccess { mapResponse ->
        context.json(mapResponse)
        return@onSuccess
      }
      .onFailure {
        context.fail(422)
        return@onFailure
      }
    }
  }

  private fun getTransactions(context: RoutingContext) {
    val clientId: Int

    try {
      clientId = context.pathParam("id").toInt()
    } catch (e: Exception) {
      context.fail(422, e)
      return
    }

    //Getting the current client data
    dbClient.preparedQuery("SELECT * FROM clients WHERE id = $1")
      .execute(Tuple.of(clientId))
      .compose { clientResult ->
        if (clientResult.size() == 0) {
          context.fail(404)
          return@compose Future.failedFuture("Client doesn't exists")
        }

        val client = clientResult.iterator().next()
        val accountLimit = client.getInteger("account_limit")
        val balance = client.getInteger("balance")
        val formattedTime = formatInstantToUTC(Instant.now())

        val balanceData = mapOf(
          "total" to balance,
          "data_extrato" to formattedTime,
          "limite" to accountLimit
        )

        //Getting the last 10 transactions
        dbClient.preparedQuery("SELECT * FROM transactions WHERE client_id = $1 ORDER BY created_at DESC LIMIT 10")
          .execute(Tuple.of(clientId))
          .compose { transactionsResult -> Future.succeededFuture(Pair(balanceData, transactionsResult)) }
      }
      .onSuccess { (balanceData, transactionsResult) ->
        val lastTenTransactions: MutableList<Map<String, Any>> = mutableListOf()
        transactionsResult.iterator().forEach {
          val amount = it.getInteger("amount")
          val type = it.getString("type")
          val description = it.getString("description")
          val createdAt = formatInstantToUTC(it.getLocalDateTime("created_at").toInstant(ZoneOffset.UTC))
          lastTenTransactions.addLast(mapOf(
            "valor" to amount,
            "tipo" to type,
            "descricao" to description,
            "realizada_em" to createdAt
          ))
        }
        context.json(mapOf(
          "saldo" to balanceData,
          "ultimas_transacoes" to lastTenTransactions
        ))
      }
      .onFailure {
        context.fail(422)
        return@onFailure
      }
  }

  private fun formatInstantToUTC(instant: Instant): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
      .withZone(ZoneOffset.UTC)
    return formatter.format(instant)
  }
}
