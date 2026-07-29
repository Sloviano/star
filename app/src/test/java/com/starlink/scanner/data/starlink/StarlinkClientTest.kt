package com.starlink.scanner.data.starlink

import com.starlink.scanner.domain.StarlinkError
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import spacex.api.device.DeviceGrpc
import spacex.api.device.DeviceInfo
import spacex.api.device.DishGetStatusResponse
import spacex.api.device.GetDeviceInfoResponse
import spacex.api.device.Request
import spacex.api.device.Response

/** Verifies StarlinkClient against an in-process mock of SpaceX.API.Device.Device/Handle. */
class StarlinkClientTest {

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel

    private fun start(service: DeviceGrpc.DeviceImplBase) {
        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
    }

    @After
    fun tearDown() {
        if (::channel.isInitialized) channel.shutdownNow()
        if (::server.isInitialized) server.shutdownNow()
    }

    @Test
    fun getStatus_mapsDishId() = runTest {
        start(object : DeviceGrpc.DeviceImplBase() {
            override fun handle(request: Request, obs: StreamObserver<Response>) {
                // get_status is the primary call; the dish ID rides along in its device info.
                assertEquals(Request.RequestCase.GET_STATUS, request.requestCase)
                obs.onNext(
                    Response.newBuilder().setDishGetStatus(
                        DishGetStatusResponse.newBuilder().setDeviceInfo(DEVICE_INFO)
                    ).build()
                )
                obs.onCompleted()
            }
        })

        val info = StarlinkClient(channel).fetchDeviceInfo()

        assertEquals("ut01000000-00000000-00001234", info.dishId)
    }

    @Test
    fun unimplementedGetStatus_fallsBackToGetDeviceInfo() = runTest {
        start(object : DeviceGrpc.DeviceImplBase() {
            override fun handle(request: Request, obs: StreamObserver<Response>) {
                when (request.requestCase) {
                    Request.RequestCase.GET_STATUS ->
                        obs.onError(Status.UNIMPLEMENTED.asRuntimeException())
                    Request.RequestCase.GET_DEVICE_INFO -> {
                        obs.onNext(
                            Response.newBuilder().setGetDeviceInfo(
                                GetDeviceInfoResponse.newBuilder().setDeviceInfo(
                                    DEVICE_INFO.toBuilder().setId("via-device-info").build()
                                )
                            ).build()
                        )
                        obs.onCompleted()
                    }
                    else -> obs.onError(Status.INVALID_ARGUMENT.asRuntimeException())
                }
            }
        })

        val info = StarlinkClient(channel).fetchDeviceInfo()

        assertEquals("via-device-info", info.dishId)
    }

    @Test
    fun unavailableServer_throwsUnavailable() = runTest {
        start(object : DeviceGrpc.DeviceImplBase() {
            override fun handle(request: Request, obs: StreamObserver<Response>) {
                obs.onError(Status.UNAVAILABLE.asRuntimeException())
            }
        })

        val error = runCatching { StarlinkClient(channel).fetchDeviceInfo() }.exceptionOrNull()
        assertTrue("expected Unavailable, got $error", error is StarlinkError.Unavailable)
    }

    private companion object {
        val DEVICE_INFO: DeviceInfo = DeviceInfo.newBuilder()
            .setId("ut01000000-00000000-00001234")
            .setHardwareVersion("rev3_proto2")
            .setSoftwareVersion("2024.10.0")
            .setCountryCode("US")
            .build()
    }
}
